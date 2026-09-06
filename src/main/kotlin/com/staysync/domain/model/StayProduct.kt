package com.staysync.domain.model

/**
 * 통합 검색 결과의 표준 숙박 상품 단위 (비영속) — 네 단위 모델의 조합.
 *
 * 숙소([Property]) × 객실([RoomType])이 상품의 정체성을, 요금([Price])과 재고 판정([Availability])이
 * 특정 검색 조건(날짜·인원)에서의 판매 정보를 담는다. 검색 때마다 정규화가 어댑터 응답을 매핑·판정과
 * 합쳐 만들며 영속화하지 않는다. 고객은 출처 공급사와 무관하게 동일한 구조로 상품을 받는다.
 *
 * 조식 포함 여부는 같은 객실이라도 공급사마다 다른 "상품의 조건"이라 요금이 아닌 상품의 속성으로 둔다 —
 * 총액만 나란히 두면 조건이 다른 상품을 같은 것처럼 비교하게 되기 때문에, 가격 옆에 항상 함께 노출한다.
 *
 * @property property 표준 숙소 (내부 대리키 기반)
 * @property roomType 표준 객실 타입
 * @property breakfastIncluded 총액에 조식이 포함되는지 — 상품의 조건
 * @property availability 요청 기간 전체에 대한 가용성 판정 (3상태)
 * @property supplier 출처 공급사
 * @property price 표준 요금 묶음
 */
data class StayProduct(
    val property: Property,
    val roomType: RoomType,
    val breakfastIncluded: Boolean,
    val availability: Availability,
    val supplier: Supplier,
    val price: Price,
)
