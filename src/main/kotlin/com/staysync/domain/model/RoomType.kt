package com.staysync.domain.model

/**
 * 표준 객실 모델 (비영속).
 *
 * 단위는 개별 물리 객실이 아니라 **객실 타입**이다 — 두 공급사 모두 요금·재고를 객실 타입 단위로 주고
 * 물리 객실은 노출하지 않으므로, 표준도 주어진 구조를 따른다. 정규화가 매핑 엔티티(RoomTypeEntity)에서
 * 투영해 만들며, 식별자는 내부 대리키다.
 * 검증된 엔티티의 투영이라 재검증하지 않는다 — 유효성은 경계(Bean Validation)와 엔티티가 이미 보장한다.
 */
data class RoomType(
    val id: Long,
    val name: String,
    val maxOccupancy: Int,
)
