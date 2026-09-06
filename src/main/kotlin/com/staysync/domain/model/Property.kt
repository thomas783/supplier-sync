package com.staysync.domain.model

/**
 * 표준 숙소 모델 (비영속).
 *
 * 검색 결과에 실리는 숙소의 표준 표현으로, 정규화가 매핑 엔티티(PropertyEntity)에서 투영해 만든다.
 * 식별자는 공급사 코드가 아닌 내부 대리키다 — 공급사 코드는 어댑터 경계 밖으로 나오지 않는다.
 * 검증된 엔티티의 투영이라 재검증하지 않는다 — 유효성은 경계(Bean Validation)와 엔티티가 이미 보장한다.
 */
data class Property(
    val id: Long,
    val name: String,
)
