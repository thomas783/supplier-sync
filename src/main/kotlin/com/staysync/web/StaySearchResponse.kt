package com.staysync.web

import com.staysync.domain.model.Availability
import com.staysync.domain.model.StayProduct
import com.staysync.domain.model.Supplier
import com.staysync.search.StaySearchResult

/**
 * 통합 검색 응답 DTO — 도메인 모델을 그대로 노출하지 않고 웹 표현(docs/API.md)으로 투영한다.
 *
 * 상품 항목은 표준 모델의 조합([StayProduct])을 그대로 비추는 중첩 구조다 — 숙소·객실·요금·가용성
 * 네 단위가 응답에서도 같은 묶음으로 보인다. 모양이 같아도 전용 DTO 를 거치는 이유는 도메인 리팩터링이
 * 곧바로 공개 계약 변경이 되지 않게 하기 위해서다.
 *
 * 가용성 노출 정책이 여기서 실현된다: 확정된 것([Availability.Determined])만 싣고 미확정은 제외한다 —
 * 확실하지 않은 재고를 파는 것은 오버부킹으로, 매진이라 단정하는 것은 거짓 정보로 이어지기 때문이다.
 */
data class StaySearchResponse(
    val stayProducts: List<StayProductResponse>,
    val errors: List<SupplierErrorResponse>,
) {
    companion object {
        fun from(result: StaySearchResult): StaySearchResponse = StaySearchResponse(
            // toWire 가 null 을 주는 상품(미확정)은 응답에서 빠진다
            stayProducts = result.stays.mapNotNull { toWire(it) },
            errors = result.errors.map { SupplierErrorResponse(it.supplier, it.reason) },
        )

        private fun toWire(product: StayProduct): StayProductResponse? {
            // 노출 정책의 전부 — Determined(가능·확정 매진)만 통과하고, 캐스트가 실패하는 경우는
            // Undetermined 뿐이다. 이후 코드는 스마트 캐스트로 availableRooms 에 바로 접근한다
            val availability = product.availability as? Availability.Determined ?: return null
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
                    isAvailable = availability is Availability.Available,
                    availableRooms = availability.availableRooms, // SoldOut 이면 getter 가 0을 내놓는다
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
    val breakfastIncluded: Boolean,
    val availability: AvailabilityResponse,
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
    val isAvailable: Boolean,
    val availableRooms: Int,
)

/** 표준 요금 (docs/API.md): 정산 기준인 gross 총액 + 표시용 평균 1박가 + 통화. */
data class PriceResponse(
    val totalAmount: Long,
    val averageNightlyAmount: Long,
    val currency: String,
)

data class SupplierErrorResponse(
    val supplier: Supplier,
    val reason: String,
)
