package com.staysync.domain.model

/**
 * 환산된 금액 — 원 통화 원가와 KRW 환산가를 각자 통화를 달고 함께 보존한다 (docs/CURRENCY.md).
 *
 * [converted]는 언제나 KRW(공급사 횡단 비교·정렬·표시의 단일 기준), [original]은 공급사가 준 원 통화
 * 금액(실제 청구에 가까운 값)이다. 적용 환율은 한 [Price] 안에서 총액·평균이 공유하므로 여기 두지 않고
 * Price 수준에 둔다.
 */
data class ExchangedMoney(
    val original: Money,
    val converted: Money,
)
