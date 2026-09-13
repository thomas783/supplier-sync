package com.staysync.resilience

import com.staysync.domain.model.Supplier
import com.staysync.observability.SupplierMetrics
import io.github.resilience4j.bulkhead.BulkheadRegistry
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import io.github.resilience4j.reactor.bulkhead.operator.BulkheadOperator
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator
import io.github.resilience4j.reactor.retry.RetryOperator
import io.github.resilience4j.retry.RetryRegistry
import org.springframework.stereotype.Component
import reactor.core.publisher.Mono

/**
 * 공급사 호출에 bulkhead·재시도·서킷 브레이커·계측을 입힌다 (정책과 근거는 docs/INTEGRATION.md, docs/MONITORING.md).
 * 인스턴스는 공급사별(A/B)로 분리되어 한 공급사의 장애·부하가 다른 공급사의 차단·재시도·동시성 판단에 섞이지 않는다.
 *
 * 검색(재고·요금) 경로의 적용 순서 — CircuitBreaker(안쪽) → Metrics → Retry → Bulkhead(바깥쪽):
 * - 재시도 각 시도가 서킷의 실패 창에 기록되고, 서킷이 open 이면 첫 시도가 즉시 차단되어 반복 실패하는
 *   공급사에 부하를 더 주지 않는다. (재시도 판별이 CallNotPermittedException 을 제외하므로 차단된
 *   호출을 다시 두드리지도 않는다)
 * - 계측이 서킷 밖·재시도 안에 있어 각 시도가 개별 기록되고(재시도 대기가 지연 분포에 안 섞임),
 *   차단된 시도도 circuit_open 으로 잡힌다.
 * - Bulkhead 가 최외곽이라 "재시도까지 포함한 한 논리적 호출"이 공급사별 동시성 슬롯 하나를 점유한다.
 *   실제 큐잉은 검색 팬아웃의 Reactor flatMap([searchConcurrency])이 논블로킹으로 하고, bulkhead 는
 *   같은 상한을 선언·계측하는 가드다(max-wait-duration 0 이라 권한이 없으면 즉시 실패 — 팬아웃이 이미
 *   같은 수로 구독을 제한하므로 권한은 항상 있다).
 */
@Component
class SupplierResilience(
    private val retryRegistry: RetryRegistry,
    private val circuitBreakerRegistry: CircuitBreakerRegistry,
    private val bulkheadRegistry: BulkheadRegistry,
    private val metrics: SupplierMetrics,
) {
    fun <T> decorate(supplier: Supplier, mono: Mono<T>): Mono<T> {
        val retry = retryRegistry.retry(RetryPath.SEARCH.instanceName(supplier))
        val circuitBreaker = circuitBreakerRegistry.circuitBreaker(supplier.name)
        val bulkhead = bulkheadRegistry.bulkhead(supplier.name)
        return metrics.instrument(supplier, mono.transformDeferred(CircuitBreakerOperator.of(circuitBreaker)))
            .transformDeferred(RetryOperator.of(retry))
            .transformDeferred(BulkheadOperator.of(bulkhead))
    }

    /**
     * 공급사별 검색 동시 호출 상한 — bulkhead 설정이 단일 원천이다. 검색 팬아웃이 이 값을 읽어 논블로킹으로
     * 큐잉하고(Reactor flatMap), 같은 bulkhead 가 세마포어로 그 상한을 선언·계측한다. 한 수를 두 곳에
     * 나눠 선언하지 않으려고, 동시성 수는 여기(bulkhead)에서만 읽는다.
     */
    fun searchConcurrency(supplier: Supplier): Int =
        bulkheadRegistry.bulkhead(supplier.name).bulkheadConfig.maxConcurrentCalls

    /**
     * 동기화(블로킹) 경로 — 서킷 없이 재시도만 입힌다. 배치 성격이라 시도 횟수가 더 많고
     * ([RetryPath.SYNC]), 하루 1회 + 수동 트리거뿐이라 반복 호출을 차단할 서킷의 효용이 없다.
     * 시도 사이의 대기는 호출 스레드가 그대로 잠든다 — 배치라 허용되는 비용이다.
     */
    fun <T> decorateSyncRetry(supplier: Supplier, call: () -> T): T =
        retryRegistry.retry(RetryPath.SYNC.instanceName(supplier)).executeSupplier(call)
}
