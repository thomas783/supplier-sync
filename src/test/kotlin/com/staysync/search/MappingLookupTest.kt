package com.staysync.search

import com.staysync.domain.model.Property
import com.staysync.domain.model.RoomType
import com.staysync.domain.model.Supplier
import com.staysync.supplier.SupplierStayProduct
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.LocalDate

class MappingLookupTest {

    private val lookup = MappingLookup(
        supplier = Supplier.A,
        propertyByCode = mapOf("A-10023" to Property(id = 10, name = "Riverside Hotel Seoul")),
        roomTypeByKey = mapOf((10L to "DLX-TWN") to RoomType(id = 100, name = "Deluxe Twin", maxOccupancy = 2)),
    )

    private fun product(propertyCode: String = "A-10023", roomTypeCode: String = "DLX-TWN") = SupplierStayProduct(
        supplierPropertyCode = propertyCode,
        propertyName = "응답의 숙소명",
        supplierRoomTypeCode = roomTypeCode,
        roomTypeName = "응답의 객실명",
        maxOccupancy = 9,
        breakfastIncluded = false,
        currency = "KRW",
        grossTotalAmount = 200_000,
        remainingByDate = mapOf(LocalDate.of(2026, 9, 1) to 3),
    )

    @Test
    fun `매핑된 코드 - 표준 모델 쌍으로 치환되고 표시 속성은 매핑이 원천이다`() {
        val (property, roomType) = requireNotNull(lookup.resolve(product()))

        assertEquals(10L, property.id)
        assertEquals("Riverside Hotel Seoul", property.name) // 검색 응답의 사본이 아니라 매핑 값
        assertEquals(100L, roomType.id)
        assertEquals("Deluxe Twin", roomType.name)
        assertEquals(2, roomType.maxOccupancy)
    }

    @Test
    fun `매핑에 없는 숙소 코드 - null 로 걸러진다`() {
        assertNull(lookup.resolve(product(propertyCode = "A-99999")))
    }

    @Test
    fun `매핑에 없는 객실 코드 - null 로 걸러진다`() {
        assertNull(lookup.resolve(product(roomTypeCode = "NO-SUCH")))
    }

    @Test
    fun `같은 객실 코드가 여러 숙소에 있어도 숙소별로 판별된다`() {
        // 객실 코드는 숙소 안에서만 유일하다 — (숙소 id, 객실 코드) 합성 키가 코드 단독 키로 회귀하면 실패한다
        val sharedCodeLookup = MappingLookup(
            supplier = Supplier.B,
            propertyByCode = mapOf(
                "B-1" to Property(id = 1, name = "숙소 하나"),
                "B-2" to Property(id = 2, name = "숙소 둘"),
            ),
            roomTypeByKey = mapOf(
                (1L to "R-401") to RoomType(id = 11, name = "첫째 객실", maxOccupancy = 2),
                (2L to "R-401") to RoomType(id = 22, name = "둘째 객실", maxOccupancy = 3),
            ),
        )

        val (property, roomType) = requireNotNull(
            sharedCodeLookup.resolve(product(propertyCode = "B-2", roomTypeCode = "R-401")),
        )
        assertEquals(2L, property.id)
        assertEquals(22L, roomType.id)
    }
}
