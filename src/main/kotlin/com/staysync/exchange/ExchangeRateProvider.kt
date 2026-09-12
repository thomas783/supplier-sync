package com.staysync.exchange

import java.math.BigDecimal
import java.util.Currency

/**
 * 원 통화 → KRW 환율 조회 포트 (docs/CURRENCY.md).
 *
 * 정규화가 원 통화 금액을 표준 KRW 로 환산할 때 이 포트로 환율을 얻는다. 구현은 갈아끼운다 —
 * 현재는 단일 통화(KRW) 범위라 no-op(항상 1)이고, 외화 공급사가 추가되면 주기 캐시 구현으로 교체한다.
 */
interface ExchangeRateProvider {
    /** [currency] → KRW 환율(로컬 1단위당 KRW). 검색 시점 스냅샷. */
    fun rate(currency: Currency): BigDecimal
}
