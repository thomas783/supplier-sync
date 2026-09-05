package com.staysync.domain.model

/**
 * StayProduct 의 가용성 3상태.
 *
 * "모르는 0"과 "확실한 0"을 구분한다 — 재고 데이터가 불완전할 때 파는 것은 오버부킹(고객 피해)으로,
 * 매진이라 단정하는 것은 거짓 정보로 이어지므로 둘은 다른 상태여야 한다.
 *
 * - [Available] 병목 최소값 ≥ 1. 잔여 수와 함께 노출한다.
 * - [SoldOut] 요청 기간의 모든 날짜 데이터가 있고 최소값 = 0. 매진 표시와 함께 노출한다 —
 *   취소로 재고가 생겼을 때 알림을 받는 기능 등 후속 기능의 진입점.
 * - [Undetermined] 요청 기간의 날짜 누락. 응답에서 제외한다.
 */
sealed interface Availability {

    /** 요청 기간 전체를 통으로 예약할 수 있는 객실이 [availableRooms]개 있다. */
    data class Available(val availableRooms: Int) : Availability {
        init {
            require(availableRooms >= 1) { "availableRooms must be at least 1: $availableRooms" }
        }
    }

    /** 확정 매진 — 모든 날짜의 재고를 확인했고 전 기간 예약 가능 수가 0이다. */
    data object SoldOut : Availability

    /** 미확정 — 재고 데이터가 불완전해 매진인지 아닌지 단정할 수 없다. */
    data object Undetermined : Availability
}
