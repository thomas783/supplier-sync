package com.staysync.observability

import com.staysync.domain.model.Availability
import com.staysync.domain.model.Supplier
import com.staysync.supplier.DefectReason
import com.staysync.supplier.SupplierCallException
import io.github.resilience4j.circuitbreaker.CallNotPermittedException
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.springframework.stereotype.Component
import reactor.core.publisher.Mono

/**
 * 공급사 연동 관측의 계측 지점 — 지표 이름·태그의 단일 원천이다 (설계와 근거는 docs/MONITORING.md).
 */
@Component
class SupplierMetrics(
    private val registry: MeterRegistry,
) {

    /**
     * 재고·요금 조회를 `supplier.stayproducts.fetch` 타이머로 계측한다 — count 로 성공률·타임아웃
     * 비율·한도 초과 빈도를, 분포(백분위 히스토그램)로 응답 지연(p95/p99)을 낸다. WebClient 자동 계측은
     * HTTP 상태 기준이라 B 의 실패(HTTP 200 + resultCode)를 성공으로 세므로, 어댑터의 판정 결과를 아는
     * 이 커스텀 타이머가 진실의 원천이다.
     *
     * 기록 단위는 **시도(attempt)** 다 — 재시도의 각 시도가 개별 기록되어 재시도 대기 시간이 지연 분포를
     * 오염시키지 않고, 복구된 순단도 실패 시도로 남는다. 서킷이 차단한 시도는 원격으로 나가지 않았어도
     * `circuit_open` 으로 센다 — 차단은 곧 상품 노출 축소라 그 빈도 자체가 신호다.
     */
    fun <T> instrument(supplier: Supplier, mono: Mono<T>): Mono<T> = Mono.defer {
        val sample = Timer.start(registry)
        mono
            .doOnSuccess { record(sample, supplier, "success") }
            .doOnError { record(sample, supplier, outcomeOf(it)) }
    }

    /**
     * 미매핑 스킵 집계 — 숙소 목록에 없던 상품이 재고 응답에 나타나 검색에서 빠질 때 센다.
     * 오르면 "동기화가 밀렸다"는 신호라, 수동 동기화 트리거라는 운영 액션으로 직결된다.
     */
    fun recordUnmappedProperty(supplier: Supplier) = recordUnmapped(supplier, "property")

    fun recordUnmappedRoomType(supplier: Supplier) = recordUnmapped(supplier, "roomType")

    /**
     * 가용성 판정 분포 집계 — 엄격 판정이 조용히 상품을 응답에서 빼는 구조라, 이 분포가 보수적 노출
     * 정책의 기회비용을 정량화하는 유일한 창이다. `undetermined` 비율 상승은 공급사 재고 데이터 품질
     * 저하의 조기 신호다. 미확정은 표준 모델에 없는 부재(null)지만, 관측은 제외되기 전 여기서 잡는다.
     */
    fun recordAvailability(supplier: Supplier, availability: Availability?) {
        val result = when {
            availability == null -> "undetermined"
            availability.isAvailable -> "available"
            else -> "sold_out"
        }
        registry.counter(AVAILABILITY_COUNTER, "supplier", supplier.name, "result", result).increment()
    }

    private fun recordUnmapped(supplier: Supplier, level: String) {
        registry.counter(UNMAPPED_COUNTER, "supplier", supplier.name, "level", level).increment()
    }

    /**
     * 결함 격리 집계 — 변환 관문([com.staysync.supplier.ConversionGate])이 결함으로 버린 항목을 사유별로
     * 센다. 검색 경로(admit)와 동기화 경로(defectOf) 양쪽 드롭이 여기 모인다. 미매핑(`unmapped`)과 층위가
     * 다르다 — 미매핑은 "우리 매핑의 공백", 이건 "공급사 데이터의 결함"이다. (전체 격리 저장은 미구현 —
     * 카운터만 우선 도입, docs/QUARANTINE.md)
     */
    fun recordQuarantined(supplier: Supplier, reason: DefectReason) {
        registry.counter(QUARANTINED_COUNTER, "supplier", supplier.name, "reason", reason.name).increment()
    }

    private fun record(sample: Timer.Sample, supplier: Supplier, outcome: String) {
        sample.stop(registry.timer(TIMER_NAME, "supplier", supplier.name, "outcome", outcome))
    }

    private fun outcomeOf(t: Throwable): String = when {
        t is CallNotPermittedException -> "circuit_open"
        t is SupplierCallException && t.timedOut -> "timeout"
        // 한도 초과를 failure 에 묻지 않는 이유: 429 관측이 대규모 대응(캐시 등)의 전환 트리거다 (docs/INTEGRATION.md)
        t is SupplierCallException && t.rateLimited -> "rate_limited"
        else -> "failure"
    }

    companion object {
        const val TIMER_NAME = "supplier.stayproducts.fetch"
        const val UNMAPPED_COUNTER = "supplier.stayproducts.unmapped"
        const val AVAILABILITY_COUNTER = "supplier.stayproducts.availability"
        const val QUARANTINED_COUNTER = "supplier.stayproducts.quarantined"
    }
}
