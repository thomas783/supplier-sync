package com.staysync.observability

import com.staysync.domain.model.Availability
import com.staysync.domain.model.Supplier
import com.staysync.supplier.DefectReason
import com.staysync.supplier.SupplierCallException
import io.github.resilience4j.circuitbreaker.CallNotPermittedException
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono

/** outcome·태그 분류가 지표로 정확히 갈리는지 고정한다 — 파생 값(성공률·비율)의 전제다. */
class SupplierMetricsTest {

    private val registry = SimpleMeterRegistry()
    private val metrics = SupplierMetrics(registry)

    private fun timerCount(outcome: String): Long =
        registry.find(SupplierMetrics.TIMER_NAME)
            .tags("supplier", "A", "outcome", outcome)
            .timer()?.count() ?: 0

    private fun counterValue(name: String, vararg tags: String): Double =
        registry.find(name).tags(*tags).counter()?.count() ?: 0.0

    private fun recordError(t: Throwable) {
        runCatching { metrics.instrument(Supplier.A, Mono.error<String>(t)).block() }
    }

    @Test
    fun `성공과 실패가 outcome 태그로 갈려 기록된다`() {
        metrics.instrument(Supplier.A, Mono.just("ok")).block()
        recordError(SupplierCallException(Supplier.A, "HTTP 500", retryable = true))

        assertEquals(1, timerCount("success"))
        assertEquals(1, timerCount("failure"))
    }

    @Test
    fun `무응답은 가장 비싼 실패라 별도 outcome 으로 분리된다`() {
        recordError(SupplierCallException(Supplier.A, "timeout (no response)", retryable = true, timedOut = true))

        assertEquals(1, timerCount("timeout"))
        assertEquals(0, timerCount("failure"))
    }

    @Test
    fun `한도 초과는 대규모 대응의 전환 트리거라 failure 에 묻히지 않는다`() {
        recordError(SupplierCallException(Supplier.A, "HTTP 429", retryable = true, rateLimited = true))

        assertEquals(1, timerCount("rate_limited"))
        assertEquals(0, timerCount("failure"))
    }

    @Test
    fun `서킷 차단은 원격에 나가지 않았어도 circuit_open 으로 센다`() {
        recordError(CallNotPermittedException.createCallNotPermittedException(CircuitBreaker.ofDefaults("A")))

        assertEquals(1, timerCount("circuit_open"))
    }

    @Test
    fun `역직렬화 실패는 스키마 드리프트 신호라 decode_error 로 분리된다`() {
        recordError(SupplierCallException(Supplier.A, "decode failed", retryable = false, decodeError = true))

        assertEquals(1, timerCount("decode_error"))
        assertEquals(0, timerCount("failure"))
    }

    @Test
    fun `미매핑 스킵이 수준별 태그로 집계된다`() {
        metrics.recordUnmappedProperty(Supplier.A)
        metrics.recordUnmappedRoomType(Supplier.A)
        metrics.recordUnmappedRoomType(Supplier.A)

        assertEquals(1.0, counterValue(SupplierMetrics.UNMAPPED_COUNTER, "supplier", "A", "level", "property"))
        assertEquals(2.0, counterValue(SupplierMetrics.UNMAPPED_COUNTER, "supplier", "A", "level", "roomType"))
    }

    @Test
    fun `가용성 판정이 3상태 분포로 집계된다`() {
        metrics.recordAvailability(Supplier.A, Availability(availableRooms = 2))
        metrics.recordAvailability(Supplier.A, Availability(availableRooms = 0))
        metrics.recordAvailability(Supplier.A, null) // 미확정 — 모델 밖의 부재지만 관측은 유지

        assertEquals(1.0, counterValue(SupplierMetrics.AVAILABILITY_COUNTER, "supplier", "A", "result", "available"))
        assertEquals(1.0, counterValue(SupplierMetrics.AVAILABILITY_COUNTER, "supplier", "A", "result", "sold_out"))
        assertEquals(1.0, counterValue(SupplierMetrics.AVAILABILITY_COUNTER, "supplier", "A", "result", "undetermined"))
    }

    @Test
    fun `결함 격리가 사유별로 집계된다`() {
        metrics.recordQuarantined(Supplier.A, DefectReason.INVALID_PRICE)
        metrics.recordQuarantined(Supplier.A, DefectReason.INVALID_PRICE)
        metrics.recordQuarantined(Supplier.A, DefectReason.DUPLICATE_DATE)

        assertEquals(2.0, counterValue(SupplierMetrics.QUARANTINED_COUNTER, "supplier", "A", "reason", "INVALID_PRICE"))
        assertEquals(1.0, counterValue(SupplierMetrics.QUARANTINED_COUNTER, "supplier", "A", "reason", "DUPLICATE_DATE"))
    }
}
