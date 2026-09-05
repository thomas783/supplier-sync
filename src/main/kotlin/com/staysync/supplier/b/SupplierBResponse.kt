package com.staysync.supplier.b

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import java.time.LocalDate

/**
 * Supplier B 원시 응답 DTO. 이 패키지 밖으로 새어 나가지 않는다.
 * 특징: 숙박 전체 총액(gross, 세금 포함), 실패해도 HTTP 200 + 본문 resultCode 로 표현.
 * 모든 응답이 `resultCode`/`resultMessage`/`data` 봉투를 쓰므로 봉투를 제네릭 하나로 표현한다.
 */

@JsonIgnoreProperties(ignoreUnknown = true)
data class SupplierBBaseResponse<T>(
    val resultCode: String,
    val resultMessage: String? = null,
    val data: T? = null,
) {
    companion object {
        const val SUCCESS_CODE = "0000"
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class SupplierBPropertiesData(val items: List<SupplierBProperty> = emptyList())

@JsonIgnoreProperties(ignoreUnknown = true)
data class SupplierBProperty(
    val propertyId: String,
    val propertyName: String,
    val rooms: List<SupplierBRoom> = emptyList(),
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SupplierBRoom(
    val roomId: String,
    val roomName: String,
    val maxOccupancy: Int,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SupplierBSearchData(val items: List<SupplierBSearchItem> = emptyList())

@JsonIgnoreProperties(ignoreUnknown = true)
data class SupplierBSearchItem(
    val propertyId: String,
    val propertyName: String,
    val roomId: String,
    val roomName: String,
    val maxOccupancy: Int,
    val breakfastIncluded: Boolean,
    val currency: String,
    val totalPrice: Long,
    val taxIncluded: Boolean,
    val inventory: List<SupplierBInventory> = emptyList(),
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SupplierBInventory(
    val date: LocalDate,
    val remainingRooms: Int,
)
