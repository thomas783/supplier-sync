package com.staysync.domain.model

import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Currency

/**
 * 표준 요금 묶음 (docs/CURRENCY.md).
 *
 * 두 공급사가 공통으로 표현 가능한 유일한 교집합은 "숙박 기간 전체의 세금 포함 총액(gross)"이다.
 * - 공급사 A: 날짜별 (단가 + 세금액)을 합산하여 gross 총액 산출
 * - 공급사 B: 기간 총액을 그대로 사용 (이미 gross, 금액 분리 없음)
 *
 * 각 금액([total]·[averageNightly])은 원 통화 원가와 KRW 환산가를 함께 담은 [ExchangedMoney]다. 환율은
 * 한 Price 안에서 두 금액이 언제나 공유하는 값이라 여기 한 벌만 둔다. 정산·결제 기준은 총액이며, 평균
 * 1박가는 표시용 파생값(총액 ÷ 박수, 내림)이라 평균 × 박수가 총액과 일치하지 않을 수 있다.
 *
 * KRW 공급사에서는 [exchangeRate] = 1, `converted == original`(통화만 KRW)로 자연 degrade 한다.
 *
 * @property exchangeRate 적용 환율(원 통화 → KRW) — 검색 시점 스냅샷, 두 금액이 공유
 * @property total 세금 포함 총액 (원가 + 환산가) — 정산 기준
 * @property averageNightly 평균 1박가 (원가 + 환산가) — 총액 ÷ 박수, 내림
 */
// private 생성자 + copy() 가시성 일치로 "평균 = 총액 ÷ 박수(내림)" 불변식의 우회 경로를 막는다 — 생성은 of()로만
@ConsistentCopyVisibility
data class Price private constructor(
    val exchangeRate: BigDecimal,
    val total: ExchangedMoney,
    val averageNightly: ExchangedMoney,
) {
    companion object {
        private val KRW: Currency = Currency.getInstance("KRW")

        /**
         * 원 통화 총액과 환율로 표준 Price 를 조립한다 (docs/CURRENCY.md 변환 파이프라인).
         * - 환산 KRW 총액 = (원가 × 환율)의 내림
         * - 원가 평균 = 원가 총액 ÷ 박수(내림), 환산 평균 = 환산 총액 ÷ 박수(내림)
         */
        fun of(originalTotal: BigDecimal, currency: Currency, exchangeRate: BigDecimal, nights: Int): Price {
            require(DomainInvariants.validNights(nights)) { "nights must be positive: $nights" }
            val nightsBd = nights.toBigDecimal()
            val convertedTotal = originalTotal.multiply(exchangeRate).setScale(0, RoundingMode.FLOOR)
            val originalAvg = originalTotal.divide(nightsBd, originalTotal.scale().coerceAtLeast(0), RoundingMode.FLOOR)
            val convertedAvg = convertedTotal.divide(nightsBd, 0, RoundingMode.FLOOR)
            return Price(
                exchangeRate = exchangeRate,
                total = ExchangedMoney(Money(originalTotal, currency), Money(convertedTotal, KRW)),
                averageNightly = ExchangedMoney(Money(originalAvg, currency), Money(convertedAvg, KRW)),
            )
        }
    }
}
