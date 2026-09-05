package com.staysync.sync

import com.staysync.TestcontainersConfiguration
import com.staysync.domain.model.Supplier
import com.staysync.domain.repository.PropertyRepository
import com.staysync.domain.repository.RoomTypeRepository
import com.staysync.supplier.StayProductQuery
import com.staysync.supplier.SupplierCallException
import com.staysync.supplier.SupplierClient
import com.staysync.supplier.SupplierProperty
import com.staysync.supplier.SupplierRoomType
import com.staysync.supplier.SupplierStayProduct
import jakarta.validation.Validation
import jakarta.validation.Validator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import reactor.core.publisher.Mono

/**
 * 동기화 유스케이스 검증 — 실제 MySQL(Testcontainers) 위에서 멱등성·갱신 반영·부분 실패 격리를 확인한다.
 * 공급사는 fake 로 대체한다 — 여기의 관심사는 연동이 아니라 매핑 반영 규칙이다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import(
    TestcontainersConfiguration::class,
    PropertySyncService::class,
    PropertyMappingService::class,
    PropertySyncServiceTest.Fakes::class,
)
class PropertySyncServiceTest {

    @Autowired lateinit var service: PropertySyncService
    @Autowired lateinit var propertyRepository: PropertyRepository
    @Autowired lateinit var roomTypeRepository: RoomTypeRepository
    @Autowired lateinit var fakeA: FakeSupplierClient
    @Autowired lateinit var fakeB: FakeSupplierClient

    @BeforeEach
    fun resetFakes() {
        fakeA.propertiesToReturn = listOf(
            SupplierProperty("A-1", "리버사이드", listOf(SupplierRoomType("R1", "디럭스", 2))),
        )
        fakeA.failWith = null
        fakeB.propertiesToReturn = listOf(
            SupplierProperty("B-1", "남산 스테이", listOf(SupplierRoomType("R1", "스탠다드", 2))),
        )
        fakeB.failWith = null
    }

    @Test
    fun `동기화는 멱등하다 - 재실행해도 중복 없이 같은 내부 id 를 유지한다`() {
        service.syncAll()
        val firstId = propertyRepository.findBySupplierAndSupplierPropertyCode(Supplier.A, "A-1")!!.id

        val results = service.syncAll()

        assertEquals(2, propertyRepository.count())
        assertEquals(2, roomTypeRepository.count())
        assertEquals(firstId, propertyRepository.findBySupplierAndSupplierPropertyCode(Supplier.A, "A-1")!!.id)
        assertTrue(results.all { it.ok })
    }

    @Test
    fun `재동기화에서 표시 속성 변경이 같은 id 로 반영된다`() {
        service.syncAll()
        val before = propertyRepository.findBySupplierAndSupplierPropertyCode(Supplier.A, "A-1")!!

        fakeA.propertiesToReturn = listOf(
            SupplierProperty("A-1", "리버사이드 리뉴얼", listOf(SupplierRoomType("R1", "디럭스 트윈", 3))),
        )
        service.syncAll()

        val after = propertyRepository.findBySupplierAndSupplierPropertyCode(Supplier.A, "A-1")!!
        assertEquals(before.id, after.id) // 자연키 불변 — 내부 id 유지
        assertEquals("리버사이드 리뉴얼", after.propertyName)
        val roomType = roomTypeRepository.findByProperty_IdAndSupplierRoomTypeCode(after.id, "R1")!!
        assertEquals("디럭스 트윈", roomType.roomTypeName)
        assertEquals(3, roomType.maxOccupancy)
    }

    @Test
    fun `한 공급사의 실패가 다른 공급사 동기화를 막지 않는다`() {
        fakeB.failWith = SupplierCallException(Supplier.B, "/b/api/properties HTTP 503", retryable = true)

        val results = service.syncAll()

        val resultA = results.single { it.supplier == Supplier.A }
        val resultB = results.single { it.supplier == Supplier.B }
        assertTrue(resultA.ok)
        assertEquals(false, resultB.ok)
        assertTrue(resultB.error!!.contains("503"))
        // A 는 저장되고 B 는 없다 — 부분 실패가 전체를 무효화하지 않는다
        assertEquals(1, propertyRepository.count())
        assertEquals(Supplier.A, propertyRepository.findAll().single().supplier)
    }

    @Test
    fun `깨진 레코드는 그 레코드만 건너뛰고 나머지는 저장된다`() {
        fakeA.propertiesToReturn = listOf(
            SupplierProperty("A-1", "정상 숙소", listOf(SupplierRoomType("R1", "디럭스", 2))),
            SupplierProperty("A-2", " ", listOf(SupplierRoomType("R1", "이름 없는 숙소의 객실", 2))), // 빈 이름
        )

        val results = service.syncAll()

        val resultA = results.single { it.supplier == Supplier.A }
        assertTrue(resultA.ok)
        assertEquals(1, resultA.properties)
        assertEquals(2, resultA.skipped) // 깨진 숙소 1 + 소속 객실 1
        assertEquals("정상 숙소", propertyRepository.findBySupplierAndSupplierPropertyCode(Supplier.A, "A-1")!!.propertyName)
        assertEquals(null, propertyRepository.findBySupplierAndSupplierPropertyCode(Supplier.A, "A-2"))
    }

    @Test
    fun `갱신 데이터가 깨지면 갱신을 건너뛰고 기존 값을 유지한다`() {
        service.syncAll()

        fakeA.propertiesToReturn = listOf(
            SupplierProperty("A-1", " ", listOf(SupplierRoomType("R1", "디럭스", 2))), // 빈 이름으로 갱신 시도
        )
        val results = service.syncAll()

        assertEquals(2, results.single { it.supplier == Supplier.A }.skipped)
        // 어제까지 멀쩡했던 이름을 오늘의 깨진 데이터로 잃지 않는다
        assertEquals("리버사이드", propertyRepository.findBySupplierAndSupplierPropertyCode(Supplier.A, "A-1")!!.propertyName)
    }

    @Test
    fun `예상 밖 예외도 공급사 단위로 격리된다`() {
        fakeB.failWith = RuntimeException("boom")

        val results = service.syncAll()

        val resultB = results.single { it.supplier == Supplier.B }
        assertEquals(false, resultB.ok)
        assertTrue(resultB.error!!.contains("sync failed"))
        assertTrue(results.single { it.supplier == Supplier.A }.ok) // A 는 계속 진행된다
    }

    @Test
    fun `공급사 목록에서 사라진 숙소는 그대로 유지된다`() {
        service.syncAll()

        fakeA.propertiesToReturn = emptyList() // A 목록에서 전부 사라짐
        service.syncAll()

        // 삭제 신호가 없으므로 매핑은 유지 — 검색에서는 공급사 응답에 없어 자연히 제외된다
        assertEquals(2, propertyRepository.count())
    }

    @TestConfiguration(proxyBeanMethods = false)
    class Fakes {
        @Bean fun fakeA() = FakeSupplierClient(Supplier.A)
        @Bean fun fakeB() = FakeSupplierClient(Supplier.B)

        // @DataJpaTest 슬라이스에는 Validator 자동 구성이 없다 — 본 앱에서는 Boot 가 제공
        @Bean fun validator(): Validator = Validation.buildDefaultValidatorFactory().validator
    }
}

/** 테스트 조작이 가능한 공급사 fake — 목록 응답과 실패를 주입한다. */
class FakeSupplierClient(
    override val supplier: Supplier,
    var propertiesToReturn: List<SupplierProperty> = emptyList(),
    var failWith: Exception? = null,
) : SupplierClient {

    override fun fetchProperties(): List<SupplierProperty> {
        failWith?.let { throw it }
        return propertiesToReturn
    }

    override fun fetchStayProducts(query: StayProductQuery): Mono<List<SupplierStayProduct>> =
        Mono.just(emptyList())
}
