package com.staysync.exchange

import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.util.Currency

/**
 * 단일 통화(KRW) 범위의 no-op 환율 — 항상 1을 돌려준다 (docs/CURRENCY.md 의 "현재 범위와 발동").
 *
 * 모든 공급사가 KRW 라 환산이 항등이므로 특수 분기 없이 같은 파이프라인이 돈다. 외화로 요금을 주는
 * 공급사가 추가되는 시점에 주기 캐시 기반 실제 환율 구현으로 이 빈을 교체한다.
 */
@Component
class FixedExchangeRateProvider : ExchangeRateProvider {
    override fun rate(currency: Currency): BigDecimal = BigDecimal.ONE
}
