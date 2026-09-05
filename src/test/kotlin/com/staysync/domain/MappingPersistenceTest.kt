package com.staysync.domain

import com.staysync.TestcontainersConfiguration
import com.staysync.domain.entity.PropertyEntity
import com.staysync.domain.entity.RoomTypeEntity
import com.staysync.domain.model.Supplier
import com.staysync.domain.repository.PropertyRepository
import com.staysync.domain.repository.RoomTypeRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.test.context.ActiveProfiles

/**
 * 매핑 영속화 검증 — 실제 MySQL 8.4(Testcontainers)에서 확인한다.
 * - 자연키 UNIQUE 제약이 실DB에서 동작하는지 (같은 공급사 상품 = 같은 내부 id 보장의 근거)
 * - 감사 컬럼을 JPA Auditing 이 실제로 채우는지
 * - upsert 에 쓰는 자연키 조회가 동작하는지
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration::class)
class MappingPersistenceTest {

    @Autowired
    lateinit var propertyRepository: PropertyRepository

    @Autowired
    lateinit var roomTypeRepository: RoomTypeRepository

    @Test
    fun `저장하면 대리키가 발급되고 감사 컬럼이 채워진다`() {
        val saved = propertyRepository.saveAndFlush(
            PropertyEntity(Supplier.A, "AUDIT-1", "감사 검증 호텔"),
        )
        assertNotNull(saved.createdAt)
        assertNotNull(saved.updatedAt)
        assert(saved.id > 0)
    }

    @Test
    fun `자연키 조회로 기존 매핑의 내부 id 를 되찾는다`() {
        val saved = propertyRepository.saveAndFlush(
            PropertyEntity(Supplier.A, "FIND-1", "자연키 조회 호텔"),
        )
        val found = propertyRepository.findBySupplierAndSupplierPropertyCode(Supplier.A, "FIND-1")
        assertEquals(saved.id, found?.id)
    }

    @Test
    fun `같은 공급사에 같은 숙소 코드는 UNIQUE 제약이 거부한다`() {
        propertyRepository.saveAndFlush(PropertyEntity(Supplier.A, "DUP-1", "원본"))
        assertThrows(DataIntegrityViolationException::class.java) {
            propertyRepository.saveAndFlush(PropertyEntity(Supplier.A, "DUP-1", "중복"))
        }
    }

    @Test
    fun `공급사가 다르면 같은 숙소 코드도 별개 매핑이다`() {
        val a = propertyRepository.saveAndFlush(PropertyEntity(Supplier.A, "SAME-CODE", "A의 숙소"))
        val b = propertyRepository.saveAndFlush(PropertyEntity(Supplier.B, "SAME-CODE", "B의 숙소"))
        assert(a.id != b.id)
    }

    @Test
    fun `같은 숙소 안에서 같은 객실 코드는 UNIQUE 제약이 거부한다`() {
        val property = propertyRepository.saveAndFlush(PropertyEntity(Supplier.B, "RT-DUP", "객실 중복 검증"))
        roomTypeRepository.saveAndFlush(RoomTypeEntity(property, "R1", "디럭스", 2))
        assertThrows(DataIntegrityViolationException::class.java) {
            roomTypeRepository.saveAndFlush(RoomTypeEntity(property, "R1", "디럭스 사본", 2))
        }
    }

    @Test
    fun `다른 숙소라면 같은 객실 코드가 공존한다 - 객실 코드는 숙소 안에서만 유일`() {
        val p1 = propertyRepository.saveAndFlush(PropertyEntity(Supplier.B, "RT-P1", "숙소 1"))
        val p2 = propertyRepository.saveAndFlush(PropertyEntity(Supplier.B, "RT-P2", "숙소 2"))
        val r1 = roomTypeRepository.saveAndFlush(RoomTypeEntity(p1, "R1", "스탠다드", 2))
        val r2 = roomTypeRepository.saveAndFlush(RoomTypeEntity(p2, "R1", "스탠다드", 3))
        assert(r1.id != r2.id)

        val found = roomTypeRepository.findByProperty_IdAndSupplierRoomTypeCode(p2.id, "R1")
        assertEquals(r2.id, found?.id)
        assertEquals(3, found?.maxOccupancy)
    }
}
