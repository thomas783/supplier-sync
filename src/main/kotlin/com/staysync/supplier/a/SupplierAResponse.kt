package com.staysync.supplier.a

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import java.time.LocalDate

/**
 * Supplier A 원시 응답 DTO. 이 패키지 밖으로 새어 나가지 않는다.
 * 특징: 날짜별 1박 단가(net) + 세금 별도, 실패는 HTTP 상태 코드로 표현.
 * 모든 응답이 `{ "items": [...] }` 봉투를 쓰므로 봉투를 제네릭 하나로 표현한다.
 */

@JsonIgnoreProperties(ignoreUnknown = true)
data class SupplierABaseResponse<T>(
    val items: List<T> = emptyList(),
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SupplierAHotel(
    val hotelCode: String,
    val hotelName: String,
    val roomTypes: List<SupplierARoomType> = emptyList(),
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SupplierARoomType(
    val roomTypeCode: String,
    val roomTypeName: String,
    val maxOccupancy: Int,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SupplierAAvailabilityItem(
    val hotelCode: String,
    val hotelName: String,
    val roomTypeCode: String,
    val roomTypeName: String,
    val maxOccupancy: Int,
    val breakfastIncluded: Boolean,
    val currency: String,
    val dailyRates: List<SupplierADailyRate> = emptyList(),
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SupplierADailyRate(
    val date: LocalDate,
    val remainingRooms: Int,
    val nightlyRate: Long,
    val taxAmount: Long,
)
