package com.staysync.domain.model

/**
 * StayProduct 의 확정 가용성 — 진실은 [availableRooms] 숫자 하나다 (0 = 확정 매진, 1 이상 = 예약 가능).
 *
 * "모르는 0"(미확정)은 이 타입에 존재하지 않는다 — 판정([AvailabilityPolicy.judge])이 null 을 돌려주고
 * 정규화가 그 상품을 제외하므로, 표준 모델에 도달하는 가용성은 언제나 확정 상태다. 재고 데이터가
 * 불완전할 때 파는 것은 오버부킹(고객 피해)으로 이어지므로 "확실하지 않으면 팔지 않는다". 반면 확정
 * 매진(0)은 노출한다 — 취소로 재고가 생겼을 때 알림을 받는 기능 등 후속 기능의 진입점이다.
 */
data class Availability(val availableRooms: Int) {
    init {
        require(DomainInvariants.validRemaining(availableRooms)) { "availableRooms must be non-negative: $availableRooms" }
    }

    /** 서버 보장 파생값 — "0이면 매진"의 해석을 소비자마다 반복 구현하다 틀리는 것을 막는다. */
    val isAvailable: Boolean get() = availableRooms >= 1
}
