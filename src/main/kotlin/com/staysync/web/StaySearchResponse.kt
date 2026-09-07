package com.staysync.web

import com.staysync.domain.model.StayProduct
import io.swagger.v3.oas.annotations.media.Schema
import com.staysync.domain.model.Supplier
import com.staysync.search.StaySearchResult

/**
 * 통합 검색 응답 DTO — 도메인 모델을 그대로 노출하지 않고 웹 표현(docs/API.md)으로 투영한다.
 *
 * 상품 항목은 표준 모델의 조합([StayProduct])을 그대로 비추는 중첩 구조다 — 숙소·객실·요금·가용성
 * 네 단위가 응답에서도 같은 묶음으로 보인다. 모양이 같아도 전용 DTO 를 거치는 이유는 도메인 리팩터링이
 * 곧바로 공개 계약 변경이 되지 않게 하기 위해서다.
 *
 * 미확정 상품은 여기 도달하지 않는다 — 정규화가 이미 제외해 표준 모델([StayProduct])의 가용성은 언제나
 * 확정이므로, 이 DTO 는 노출 정책 없이 투영만 한다.
 */
data class StaySearchResponse(
    @field:Schema(description = "표준 숙박 상품 목록 — 확정된 상품만 노출(미확정은 정규화에서 제외), 확정 매진 포함")
    val stayProducts: List<StayProductResponse>,
    @field:Schema(description = "조회에 실패한 공급사와 사유 — 비어 있으면 전체 성공")
    val errors: List<SupplierErrorResponse>,
) {
    companion object {
        fun from(result: StaySearchResult): StaySearchResponse = StaySearchResponse(
            stayProducts = result.stays.map { toWire(it) },
            errors = result.errors.map { SupplierErrorResponse(it.supplier, it.reason) },
        )

        private fun toWire(product: StayProduct): StayProductResponse {
            val availability = product.availability
            return StayProductResponse(
                property = PropertyResponse(
                    id = product.property.id,
                    name = product.property.name,
                ),
                roomType = RoomTypeResponse(
                    id = product.roomType.id,
                    name = product.roomType.name,
                    maxOccupancy = product.roomType.maxOccupancy,
                ),
                breakfastIncluded = product.breakfastIncluded, // 돈이 아니라 상품의 조건 — price 밖, 상품 직속
                availability = AvailabilityResponse(
                    // 서버 보장 파생값 — 클라이언트마다 "0이면 매진"을 제각각 구현하다 틀리는 것을 막는다
                    isAvailable = availability.isAvailable,
                    availableRooms = availability.availableRooms, // 0 = 확정 매진
                ),
                supplier = product.supplier,
                price = PriceResponse(
                    totalAmount = product.price.totalAmount,
                    averageNightlyAmount = product.price.averageNightlyAmount,
                    currency = product.price.currency,
                ),
            )
        }
    }
}

/** 표준 숙박 상품의 웹 투영 — 필드 구성은 [StayProduct]의 네 단위 조합을 따른다. */
data class StayProductResponse(
    val property: PropertyResponse,
    val roomType: RoomTypeResponse,
    @field:Schema(description = "총액에 조식이 포함되는지 — 돈이 아니라 상품의 조건이라 price 밖")
    val breakfastIncluded: Boolean,
    val availability: AvailabilityResponse,
    @field:Schema(description = "출처 공급사")
    val supplier: Supplier,
    val price: PriceResponse,
)

data class PropertyResponse(
    val id: Long,
    val name: String,
)

data class RoomTypeResponse(
    val id: Long,
    val name: String,
    val maxOccupancy: Int,
)

/**
 * 확정된 가용성 (docs/API.md 노출 정책). 진실은 [availableRooms] 숫자 하나이고(0 = 확정 매진),
 * [isAvailable] 은 프론트 편의를 위한 서버 보장 파생값이다 — 별도 status 필드는 두지 않는다.
 */
data class AvailabilityResponse(
    @field:Schema(description = "예약 가능 여부 — 서버가 보장하는 편의 파생값(진실은 availableRooms)")
    val isAvailable: Boolean,
    @field:Schema(description = "요청 기간 전체를 통으로 예약할 수 있는 객실 수 — 0이면 확정 매진")
    val availableRooms: Int,
)

/** 표준 요금 (docs/API.md): 정산 기준인 gross 총액 + 표시용 평균 1박가 + 통화. */
data class PriceResponse(
    @field:Schema(description = "숙박 기간 전체의 세금 포함 총액 — 정산·결제 금액의 기준")
    val totalAmount: Long,
    @field:Schema(description = "평균 1박가 = 총액 ÷ 박수(내림) — 표시용 파생값")
    val averageNightlyAmount: Long,
    @field:Schema(description = "ISO 4217 통화 코드 — 환산 없이 원 통화 그대로")
    val currency: String,
)

data class SupplierErrorResponse(
    val supplier: Supplier,
    @field:Schema(description = "짧은 분류 문자열 — 예: timeout, HTTP 503, resultCode E503, circuit open")
    val reason: String,
)
