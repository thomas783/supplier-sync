package com.staysync.supplier

import com.staysync.domain.model.Supplier
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.LocalDate

class ConversionGateTest {

    private fun product(
        grossTotalAmount: java.math.BigDecimal = 429000.toBigDecimal(),
        currency: String = "KRW",
        remainingByDate: Map<LocalDate, Int> = mapOf(LocalDate.of(2026, 9, 1) to 3),
    ) = SupplierStayProduct(
        supplierPropertyCode = "A-10023",
        propertyName = "Riverside Hotel Seoul",
        supplierRoomTypeCode = "DLX-TWN",
        roomTypeName = "Deluxe Twin",
        maxOccupancy = 2,
        breakfastIncluded = false,
        currency = currency,
        grossTotalAmount = grossTotalAmount,
        remainingByDate = remainingByDate,
    )

    private fun roomType(code: String = "DLX-TWN", name: String = "Deluxe Twin", maxOccupancy: Int = 2) =
        SupplierRoomType(supplierRoomTypeCode = code, roomTypeName = name, maxOccupancy = maxOccupancy)

    @Test
    fun `정상 상품 - 결함 없음`() {
        assertNull(ConversionGate.defectOf(product()))
    }

    @Test
    fun `admit - 정상 상품은 그대로 통과한다`() {
        val clean = product()
        assertEquals(clean, ConversionGate.admit(Supplier.A, listOf(LocalDate.of(2026, 9, 1)), clean))
    }

    @Test
    fun `admit - 원시 날짜 목록에 중복이 있으면 제외한다 - Map 으로 접히기 전에만 보이는 결함`() {
        val duplicated = listOf(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 1))
        assertNull(ConversionGate.admit(Supplier.A, duplicated, product()))
    }

    @Test
    fun `총액 음수 - INVALID_PRICE`() {
        assertEquals(DefectReason.INVALID_PRICE, ConversionGate.defectOf(product(grossTotalAmount = (-1000).toBigDecimal())))
    }

    @Test
    fun `통화 공백 - MISSING_FIELD`() {
        assertEquals(DefectReason.MISSING_FIELD, ConversionGate.defectOf(product(currency = " ")))
    }

    @Test
    fun `음수 재고 - INVALID_INVENTORY - 매진으로 조용히 둔갑하지 않도록 관문에서 잡는다`() {
        val defective = product(remainingByDate = mapOf(LocalDate.of(2026, 9, 1) to -2))
        assertEquals(DefectReason.INVALID_INVENTORY, ConversionGate.defectOf(defective))
    }

    @Test
    fun `정상 숙소와 룸타입 - 결함 없음`() {
        assertNull(ConversionGate.defectOf(SupplierProperty("A-10023", "Riverside", listOf(roomType()))))
        assertNull(ConversionGate.defectOf(roomType()))
    }

    @Test
    fun `숙소 코드나 이름 공백 - MISSING_FIELD`() {
        assertEquals(
            DefectReason.MISSING_FIELD,
            ConversionGate.defectOf(SupplierProperty(supplierPropertyCode = " ", propertyName = "Riverside", roomTypes = emptyList())),
        )
        assertEquals(
            DefectReason.MISSING_FIELD,
            ConversionGate.defectOf(SupplierProperty(supplierPropertyCode = "A-10023", propertyName = "", roomTypes = emptyList())),
        )
    }

    @Test
    fun `룸타입 코드 공백 - MISSING_FIELD`() {
        assertEquals(DefectReason.MISSING_FIELD, ConversionGate.defectOf(roomType(code = " ")))
    }

    @Test
    fun `룸타입 정원 0 이하 - INVALID_OCCUPANCY`() {
        assertEquals(DefectReason.INVALID_OCCUPANCY, ConversionGate.defectOf(roomType(maxOccupancy = 0)))
    }
}
