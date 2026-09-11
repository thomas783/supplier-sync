package com.staysync.sync

import com.staysync.TestcontainersConfiguration
import com.staysync.domain.model.Supplier
import com.staysync.domain.repository.PropertyRepository
import com.staysync.domain.repository.RoomTypeRepository
import com.staysync.supplier.SupplierProperty
import com.staysync.supplier.SupplierRoomType
import io.micrometer.core.instrument.MeterRegistry
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.TestPropertySource

/**
 * 청크 단위 저장의 부분 실패 동작을 실제 MySQL(Testcontainers)에서 검증한다.
 *
 * 이 동작은 청크마다 독립 트랜잭션으로 커밋되는 운영 경로의 행위라, 테스트 트랜잭션이 청크 트랜잭션을
 * 흡수하는 @DataJpaTest 로는 재현되지 않는다 — 그래서 앰비언트 트랜잭션이 없는 @SpringBootTest 로 띄우고,
 * 커밋된 행을 테스트 전후로 직접 청소한다. chunk-size 를 2 로 낮춰 작은 데이터로 청크 경계를 만든다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration::class)
@TestPropertySource(
    properties = [
        "sync.chunk-size=2",
        // 기동 시 동기화(PropertySyncRunner)가 실제 공급사를 때리지 않도록 죽은 주소로 고정한다 —
        // 이 검증은 persistMappings 를 직접 호출한다
        "supplier.a.base-url=http://localhost:1",
        "supplier.b.base-url=http://localhost:1",
    ],
)
class PropertyMappingChunkFailureTest {

    @Autowired lateinit var mappingService: PropertyMappingService
    @Autowired lateinit var propertyRepository: PropertyRepository
    @Autowired lateinit var roomTypeRepository: RoomTypeRepository
    @Autowired lateinit var meterRegistry: MeterRegistry

    // 커밋되는 통합 테스트라 공유 컨테이너에 흔적을 남기지 않는다 — FK 때문에 자식(room_type)부터 지운다
    @BeforeEach
    @AfterEach
    fun clean() {
        roomTypeRepository.deleteAll()
        propertyRepository.deleteAll()
    }

    @Test
    fun `중간 청크가 실패해도 나머지 청크는 커밋되고 실패분은 failed 로 집계된다`() {
        // chunk-size=2 → [BAD, A] | [OK-1, OK-2]. 첫 청크의 BAD 는 이름이 컬럼 길이를 넘겨 INSERT 가
        // 실패하고, 그 청크는 통째로 롤백된다(같은 청크의 정상 건 A 도 함께 사라진다). 둘째 청크는 독립
        // 트랜잭션으로 커밋된다.
        val properties = listOf(
            SupplierProperty("BAD", TOO_LONG_NAME, listOf(SupplierRoomType("R1", "디럭스", 2))),
            SupplierProperty("A", "정상이지만 실패 청크에 묶인 숙소", listOf(SupplierRoomType("R1", "디럭스", 2))),
            SupplierProperty("OK-1", "정상 숙소 1", listOf(SupplierRoomType("R1", "스탠다드", 2))),
            SupplierProperty("OK-2", "정상 숙소 2", listOf(SupplierRoomType("R1", "스탠다드", 2))),
        )

        val counts = mappingService.persistMappings(Supplier.A, properties)

        // 둘째 청크만 반영된다
        assertEquals(2, counts.properties)
        assertEquals(2, counts.roomTypes)
        assertEquals(0, counts.skipped)
        // 실패한 청크의 레코드 수 = 상품 2 + 각 소속 객실 1 = 4
        assertEquals(4, counts.failed)

        // 부분 커밋: 실패 청크(BAD·A)는 남지 않고, 성공 청크(OK-1·OK-2)는 커밋되어 살아 있다
        assertEquals(2, propertyRepository.count())
        assertNull(propertyRepository.findBySupplierAndSupplierPropertyCode(Supplier.A, "BAD"))
        assertNull(propertyRepository.findBySupplierAndSupplierPropertyCode(Supplier.A, "A"))
        assertEquals(
            "정상 숙소 1",
            propertyRepository.findBySupplierAndSupplierPropertyCode(Supplier.A, "OK-1")!!.propertyName,
        )
        assertEquals(
            "정상 숙소 2",
            propertyRepository.findBySupplierAndSupplierPropertyCode(Supplier.A, "OK-2")!!.propertyName,
        )
    }

    @Test
    fun `한 청크 안의 중복 자연키는 실패가 아니라 last-wins 로 흡수된다`() {
        // chunk-size=2 → [DUP, DUP] 가 한 청크. 벌크 조회 맵을 save 직후 갱신하므로 둘째 건이 신규로
        // 오인돼 UNIQUE 위반을 내지 않고, 첫 건을 갱신한다(마지막 값 우선). 상품·객실 모두 확인한다.
        val properties = listOf(
            SupplierProperty("DUP", "첫 이름", listOf(SupplierRoomType("R1", "디럭스", 2))),
            SupplierProperty("DUP", "나중 이름", listOf(SupplierRoomType("R1", "디럭스 갱신", 3))),
        )

        val counts = mappingService.persistMappings(Supplier.A, properties)

        assertEquals(0, counts.failed) // UNIQUE 위반 없이 흡수된다
        // 물리 행은 하나이고 마지막 값이 반영된다
        assertEquals(1, propertyRepository.findAllBySupplier(Supplier.A).size)
        val saved = propertyRepository.findBySupplierAndSupplierPropertyCode(Supplier.A, "DUP")!!
        assertEquals("나중 이름", saved.propertyName)
        val room = roomTypeRepository.findByProperty_IdAndSupplierRoomTypeCode(saved.id, "R1")!!
        assertEquals("디럭스 갱신", room.roomTypeName)
        assertEquals(3, room.maxOccupancy)

        // 조용히 흡수하지 않고 지표로 드러낸다 — 중복은 공급사 계약 위반 신호
        val duplicates = meterRegistry.find("supplier.mapping.duplicate")
            .tags("supplier", "A", "level", "property").counter()?.count() ?: 0.0
        assertTrue(duplicates >= 1.0, "중복 상품 코드가 지표로 관측돼야 한다")
    }

    companion object {
        // property_name 컬럼(기본 VARCHAR(255))을 넘겨 저장 시 DB 제약 위반을 유발한다 — 앱 검증(공백·정원)은
        // 통과하지만 DB INSERT 에서 실패하는 "청크 단위 저장 실패"를 결정적으로 재현하는 수단이다.
        private val TOO_LONG_NAME = "X".repeat(512)
    }
}
