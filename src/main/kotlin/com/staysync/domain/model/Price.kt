package com.staysync.domain.model

import java.time.LocalDate

/**
 * 표준 요금 묶음.
 *
 * 두 공급사가 공통으로 표현 가능한 유일한 교집합은 "숙박 기간 전체의 세금 포함 총액(gross)"이다.
 * - 공급사 A: 날짜별 (단가 + 세금액)을 합산하여 gross 총액 산출
 * - 공급사 B: 기간 총액을 그대로 사용 (이미 gross, 금액 분리 없음)
 *
 * 정산·결제 금액의 기준은 어디까지나 [totalAmount]다. [averageNightlyAmount]는 "하루에 얼마인가"를
 * 위한 표시용 파생값(총액 ÷ 박수, 내림)이라 평균 × 박수가 총액과 일치하지 않을 수 있다.
 *
 * [nightlyRates]는 교집합 원칙의 첫 명시적 예외다 — 일자별 실측을 주는 공급사(A)만 채우고 없는
 * 공급사(B)는 빈 리스트로 둔다. 평균을 일자별로 복제해 채우는 방식은 실측이 아닌 값을 실측처럼 보이게
 * 하므로 거부했다. 항상 존재하는 값은 평균이고 일자별은 부가 정보라는 위계다.
 *
 * 조식 포함 여부는 돈이 아니라 상품의 조건이므로 여기가 아닌 [StayProduct]의 속성이다.
 *
 * 금액은 통화 최소 단위 정수(KRW는 원). 합산·내림 나눗셈뿐이라 부동소수점 오차 여지가 없다.
 * 현재 범위는 KRW만 고려하며, 환율·다중 통화는 추후 논의(docs/DESIGN_DECISIONS.md).
 *
 * @property totalAmount 세금 포함 총액 — 정산 기준
 * @property averageNightlyAmount 평균 1박가 — 총액 ÷ 박수, 내림
 * @property nightlyRates 일자별 실측 금액 — 실측을 주는 공급사만, 없으면 빈 리스트
 * @property currency ISO 4217 통화 코드. 환산하지 않고 보존한다.
 */
// private 생성자 + copy() 가시성 일치로 "평균 = 총액 ÷ 박수(내림)" 불변식의 우회 경로를 막는다 — 생성은 of()로만
@ConsistentCopyVisibility
data class Price private constructor(
    val totalAmount: Long,
    val averageNightlyAmount: Long,
    val nightlyRates: List<NightlyRate>,
    val currency: String,
) {
    init {
        require(totalAmount >= 0) { "totalAmount must be non-negative: $totalAmount" }
        require(averageNightlyAmount >= 0) { "averageNightlyAmount must be non-negative: $averageNightlyAmount" }
        require(currency.isNotBlank()) { "currency must not be blank" }
    }

    companion object {
        /** 총액과 박수로 평균 1박가(내림)를 계산해 생성한다. */
        fun of(totalAmount: Long, nights: Int, nightlyRates: List<NightlyRate>, currency: String): Price {
            require(nights > 0) { "nights must be positive: $nights" }
            return Price(
                totalAmount = totalAmount,
                averageNightlyAmount = totalAmount / nights,
                nightlyRates = nightlyRates,
                currency = currency,
            )
        }
    }
}

/** 일자별 실측 금액 항목. */
data class NightlyRate(
    val date: LocalDate,
    val amount: Long,
) {
    init {
        require(amount >= 0) { "amount must be non-negative: $amount" }
    }
}
