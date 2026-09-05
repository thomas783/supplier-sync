package com.staysync.domain.model

/**
 * 통합 검색 결과의 표준 숙박 상품 단위 (비영속).
 *
 * 두 공급사의 서로 다른 응답을 이 형태로 정규화한다. 고객은 출처 공급사와 무관하게 동일한 구조로
 * 상품을 받는다. 검색 때마다 만들어지는 런타임 모델이며 영속화하지 않는다 — 특정 검색 조건(날짜·인원)
 * 에서의 요금과 가용성이 붙은 판매 상품 하나가 검색 결과의 항목이다.
 *
 * 상품의 단위는 "숙소 × 객실 타입"이다. 두 공급사 모두 상품을 `숙소 > 객실 타입` 2단계로 표현하고
 * 요금·재고를 객실 타입 단위로 주기 때문에(개별 물리 객실은 노출하지 않음), 표준도 이를 따른다.
 *
 * 조식 포함 여부는 같은 객실이라도 공급사마다 다른 "상품의 조건"이라 요금이 아닌 상품의 속성으로 둔다 —
 * 총액만 나란히 두면 조건이 다른 상품을 같은 것처럼 비교하게 되기 때문에, 가격 옆에 항상 함께 노출한다.
 *
 * @property propertyId 내부 숙소 식별자 (공급사 코드가 아닌 자사 대리키)
 * @property propertyName 숙소명
 * @property roomTypeId 내부 객실 타입 식별자
 * @property roomTypeName 객실 타입명
 * @property maxOccupancy 객실 1실의 최대 수용 인원 (성인+아동 합산)
 * @property breakfastIncluded 총액에 조식이 포함되는지 — 상품의 조건
 * @property availability 요청 기간 전체에 대한 가용성 판정 (3상태)
 * @property supplier 출처 공급사
 * @property price 표준 요금 묶음
 */
data class StayProduct(
    val propertyId: Long,
    val propertyName: String,
    val roomTypeId: Long,
    val roomTypeName: String,
    val maxOccupancy: Int,
    val breakfastIncluded: Boolean,
    val availability: Availability,
    val supplier: Supplier,
    val price: Price,
)
