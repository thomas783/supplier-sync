package com.staysync.domain.entity

import com.staysync.domain.model.Supplier
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * 매핑 엔티티의 생성·갱신 불변식 검증 — 잘못된 값의 객체는 애초에 만들어지지 못한다.
 * DB 없이 도는 순수 단위 테스트다 (영속화 동작은 MappingPersistenceTest 가 담당).
 */
class MappingEntityValidationTest {

    private fun property() = PropertyEntity(Supplier.A, "A-1", "정상 숙소")

    @Test
    fun `빈 숙소 코드는 생성이 거부된다`() {
        assertThrows(IllegalArgumentException::class.java) { PropertyEntity(Supplier.A, " ", "이름") }
    }

    @Test
    fun `빈 숙소명은 생성도 갱신도 거부된다`() {
        assertThrows(IllegalArgumentException::class.java) { PropertyEntity(Supplier.A, "A-1", " ") }
        assertThrows(IllegalArgumentException::class.java) { property().updateFrom(propertyName = " ") }
    }

    @Test
    fun `숙소명 갱신이 반영된다`() {
        val entity = property()
        entity.updateFrom(propertyName = "바뀐 숙소")
        assertEquals("바뀐 숙소", entity.propertyName)
    }

    @Test
    fun `빈 객실 코드·이름은 생성이 거부된다`() {
        assertThrows(IllegalArgumentException::class.java) { RoomTypeEntity(property(), " ", "이름", 2) }
        assertThrows(IllegalArgumentException::class.java) { RoomTypeEntity(property(), "R1", " ", 2) }
    }

    @Test
    fun `0 이하 정원은 생성도 갱신도 거부된다`() {
        assertThrows(IllegalArgumentException::class.java) { RoomTypeEntity(property(), "R1", "이름", 0) }
        val roomType = RoomTypeEntity(property(), "R1", "이름", 2)
        assertThrows(IllegalArgumentException::class.java) { roomType.updateFrom("이름", -1) }
    }

    @Test
    fun `객실 표시 속성 갱신이 반영된다`() {
        val roomType = RoomTypeEntity(property(), "R1", "이름", 2)
        roomType.updateFrom(roomTypeName = "바뀐 객실", maxOccupancy = 3)
        assertEquals("바뀐 객실", roomType.roomTypeName)
        assertEquals(3, roomType.maxOccupancy)
    }
}
