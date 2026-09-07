package com.staysync.resilience

import com.staysync.domain.model.Supplier
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator
import io.github.resilience4j.reactor.retry.RetryOperator
import io.github.resilience4j.retry.RetryRegistry
import org.springframework.stereotype.Component
import reactor.core.publisher.Mono

/**
 * 공급사 호출에 재시도·서킷 브레이커를 입힌다 (정책과 근거는 docs/INTEGRATION.md).
 * 인스턴스는 공급사별(A/B)로 분리되어 한 공급사의 장애가 다른 공급사의 차단·재시도 판단에 섞이지 않는다.
 *
 * 검색(재고·요금) 경로의 적용 순서 — CircuitBreaker(안쪽) → Retry(바깥쪽): 재시도 각 시도가 서킷의
 * 실패 창에 기록되고, 서킷이 open 이면 첫 시도가 즉시 차단되어 반복 실패하는 공급사에 부하를 더 주지
 * 않는다. (재시도 판별이 CallNotPermittedException 을 제외하므로 차단된 호출을 다시 두드리지도 않는다)
 */
@Component
class SupplierResilience(
    private val retryRegistry: RetryRegistry,
    private val circuitBreakerRegistry: CircuitBreakerRegistry,
) {
    fun <T> decorate(supplier: Supplier, mono: Mono<T>): Mono<T> {
        val retry = retryRegistry.retry(RetryPath.SEARCH.instanceName(supplier))
        val circuitBreaker = circuitBreakerRegistry.circuitBreaker(supplier.name)
        return mono
            .transformDeferred(CircuitBreakerOperator.of(circuitBreaker))
            .transformDeferred(RetryOperator.of(retry))
    }

    /**
     * 동기화(블로킹) 경로 — 서킷 없이 재시도만 입힌다. 배치 성격이라 시도 횟수가 더 많고
     * ([RetryPath.SYNC]), 하루 1회 + 수동 트리거뿐이라 반복 호출을 차단할 서킷의 효용이 없다.
     * 시도 사이의 대기는 호출 스레드가 그대로 잠든다 — 배치라 허용되는 비용이다.
     */
    fun <T> decorateSyncRetry(supplier: Supplier, call: () -> T): T =
        retryRegistry.retry(RetryPath.SYNC.instanceName(supplier)).executeSupplier(call)
}
