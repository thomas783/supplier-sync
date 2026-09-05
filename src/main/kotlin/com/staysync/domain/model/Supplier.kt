package com.staysync.domain.model

/**
 * 연동 대상 공급사.
 *
 * 상품의 "출처"이자 내부 식별자 유일성의 네임스페이스다.
 * 같은 숙소 코드라도 공급사가 다르면 별개의 상품으로 취급한다.
 * (서로 다른 공급사 상품을 하나로 합치는 중복 병합은 추후 논의 — docs/DESIGN_DECISIONS.md)
 */
enum class Supplier {
    A,
    B,
}
