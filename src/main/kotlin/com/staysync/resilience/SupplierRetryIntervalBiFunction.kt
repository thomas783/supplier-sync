package com.staysync.resilience

import io.github.resilience4j.core.IntervalBiFunction
import io.github.resilience4j.core.functions.Either

/**
 * 재시도 대기 산정의 yml 진입점 — resilience4j 가 yml 의 `interval-bi-function` 으로 이 클래스를
 * 인스턴스화한다(판별의 `retry-exception-predicate` 와 같은 위임 방식). 규칙 자체는
 * [SupplierRetryPolicy] 에 있다.
 */
class SupplierRetryIntervalBiFunction : IntervalBiFunction<Any> {

    override fun apply(attempt: Int, either: Either<Throwable, Any>): Long =
        SupplierRetryPolicy.interval(attempt, if (either.isLeft) either.left else null)
}
