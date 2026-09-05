package com.staysync.domain.model

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
 * 일자별 실측 금액은 표준에 담지 않는다 — 실측을 주는 공급사(A)에서만 채워지는 비대칭 필드는 소비자
 * 분기를 낳으므로 교집합 원칙에 따라 버렸고, 필요해지는 시점에 어댑터 경계에서 복원할 수 있다
 * (docs/DOMAIN_MODEL.md 의 "잃는 것" 목록).
 *
 * 조식 포함 여부는 돈이 아니라 상품의 조건이므로 여기가 아닌 [StayProduct]의 속성이다.
 *
 * 금액은 통화 최소 단위 정수(KRW는 원). 합산·내림 나눗셈뿐이라 부동소수점 오차 여지가 없다.
 * 현재 범위는 KRW만 고려하며, 환율·다중 통화는 추후 논의(docs/DESIGN_DECISIONS.md).
 *
 * @property totalAmount 세금 포함 총액 — 정산 기준
 * @property averageNightlyAmount 평균 1박가 — 총액 ÷ 박수, 내림
 * @property currency ISO 4217 통화 코드. 환산하지 않고 보존한다.
 */
// private 생성자 + copy() 가시성 일치로 "평균 = 총액 ÷ 박수(내림)" 불변식의 우회 경로를 막는다 — 생성은 of()로만
@ConsistentCopyVisibility
data class Price private constructor(
    val totalAmount: Long,
    val averageNightlyAmount: Long,
    val currency: String,
) {
    init {
        require(totalAmount >= 0) { "totalAmount must be non-negative: $totalAmount" }
        require(averageNightlyAmount >= 0) { "averageNightlyAmount must be non-negative: $averageNightlyAmount" }
        require(currency.isNotBlank()) { "currency must not be blank" }
    }

    companion object {
        /** 총액과 박수로 평균 1박가(내림)를 계산해 생성한다. */
        fun of(totalAmount: Long, nights: Int, currency: String): Price {
            require(nights > 0) { "nights must be positive: $nights" }
            return Price(
                totalAmount = totalAmount,
                averageNightlyAmount = totalAmount / nights,
                currency = currency,
            )
        }
    }
}
