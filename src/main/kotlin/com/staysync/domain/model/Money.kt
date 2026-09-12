package com.staysync.domain.model

import java.math.BigDecimal
import java.util.Currency

/**
 * 한 통화로 표현된 하나의 금액 (Money 값 객체).
 *
 * 금액과 통화를 함께 들어 자기 설명적이다 — 어느 통화의 얼마인지 이 객체만으로 알 수 있다.
 * 음수 금액은 허용하지 않는다(0은 허용).
 */
data class Money(
    val amount: BigDecimal,
    val currency: Currency,
) {
    init {
        require(DomainInvariants.validAmount(amount)) { "amount must be non-negative: $amount" }
    }
}
