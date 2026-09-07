package com.staysync.resilience

import com.staysync.supplier.SupplierCallException
import java.util.function.Predicate

/**
 * 재시도 대상 판별 — resilience4j 가 yml 의 `retry-exception-predicate` 로 이 클래스를 인스턴스화한다.
 *
 * 판단 자체는 어댑터가 실패를 통일하며 붙인 [SupplierCallException.retryable] 분류에 위임한다
 * (분류 규칙: 타임아웃·5xx·429·공급사 내부오류는 재시도, 잘못된 요청·인증 실패는 제외 —
 * docs/INTEGRATION.md). 서킷이 open 일 때 던지는 CallNotPermittedException 은 이 타입이 아니므로
 * 자연히 재시도되지 않는다 — 차단을 즉시 존중한다.
 */
class RetryablePredicate : Predicate<Throwable> {
    override fun test(t: Throwable): Boolean = t is SupplierCallException && t.retryable
}
