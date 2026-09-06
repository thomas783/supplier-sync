package com.staysync.web

import com.staysync.domain.model.Availability
import com.staysync.domain.model.Price
import com.staysync.domain.model.Property
import com.staysync.domain.model.RoomType
import com.staysync.domain.model.StayProduct
import com.staysync.domain.model.Supplier
import com.staysync.search.StaySearchResult
import com.staysync.search.SupplierError
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 웹 투영의 단위 테스트 — 필드 대응(전치 버그 방지)과 가용성 노출 정책을 고정한다.
 * 숙소/객실 id 를 다른 값(10/100)으로 두어 뒤바뀌면 반드시 실패하게 한다.
 */
class StaySearchResponseTest {

    private fun product(availability: Availability) = StayProduct(
        property = Property(id = 10, name = "Riverside Hotel Seoul"),
        roomType = RoomType(id = 100, name = "Deluxe Twin", maxOccupancy = 2),
        breakfastIncluded = true,
        availability = availability,
        supplier = Supplier.A,
        price = Price.of(totalAmount = 429_000, nights = 3, currency = "KRW"),
    )

    @Test
    fun `가능 상품 - 표준 모델 네 단위가 필드 그대로 투영된다`() {
        val response = StaySearchResponse.from(
            StaySearchResult(stays = listOf(product(Availability.Available(2))), errors = emptyList()),
        )

        val item = response.stayProducts.single()
        assertEquals(10L, item.property.id)
        assertEquals("Riverside Hotel Seoul", item.property.name)
        assertEquals(100L, item.roomType.id)
        assertEquals("Deluxe Twin", item.roomType.name)
        assertEquals(2, item.roomType.maxOccupancy)
        assertTrue(item.breakfastIncluded)
        assertTrue(item.availability.isAvailable)
        assertEquals(2, item.availability.availableRooms)
        assertEquals(Supplier.A, item.supplier)
        assertEquals(429_000L, item.price.totalAmount)
        assertEquals(143_000L, item.price.averageNightlyAmount)
        assertEquals("KRW", item.price.currency)
    }

    @Test
    fun `확정 매진 - 제외가 아니라 0으로 노출되고 isAvailable 은 false 다`() {
        val response = StaySearchResponse.from(
            StaySearchResult(stays = listOf(product(Availability.SoldOut)), errors = emptyList()),
        )

        val item = response.stayProducts.single()
        assertFalse(item.availability.isAvailable)
        assertEquals(0, item.availability.availableRooms)
    }

    @Test
    fun `미확정 - 응답에서 제외되고 오류도 아니다`() {
        val response = StaySearchResponse.from(
            StaySearchResult(stays = listOf(product(Availability.Undetermined)), errors = emptyList()),
        )

        assertTrue(response.stayProducts.isEmpty())
        assertTrue(response.errors.isEmpty())
    }

    @Test
    fun `부분 실패 - 공급사 오류가 사유와 함께 그대로 실린다`() {
        val response = StaySearchResponse.from(
            StaySearchResult(stays = emptyList(), errors = listOf(SupplierError(Supplier.B, "resultCode E503"))),
        )

        assertEquals(Supplier.B, response.errors.single().supplier)
        assertEquals("resultCode E503", response.errors.single().reason)
    }
}
