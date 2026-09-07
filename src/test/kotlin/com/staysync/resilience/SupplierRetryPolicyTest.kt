package com.staysync.resilience

import com.staysync.domain.model.Supplier
import com.staysync.supplier.SupplierCallException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 재시도 정책(대기 산정·판별)의 순수 단위 테스트.
 * 지터가 섞이므로 대기는 정확값이 아니라 구간(기본 ±50%)으로 검증한다.
 */
class SupplierRetryPolicyTest {

    private fun failure(retryable: Boolean = true, rateLimited: Boolean = false) =
        SupplierCallException(Supplier.A, "test", retryable = retryable, rateLimited = rateLimited)

    @Test
    fun `일시 오류 - 짧은 기본 대기에 지터가 붙는다`() {
        val interval = SupplierRetryPolicy.interval(attempt = 1, failure = failure())
        assertTrue(interval in 100..300) { "200ms ±50% 구간을 벗어남: $interval" }
    }

    @Test
    fun `한도 초과 - 기본 대기가 길다`() {
        val interval = SupplierRetryPolicy.interval(attempt = 1, failure = failure(rateLimited = true))
        assertTrue(interval in 500..1500) { "1000ms ±50% 구간을 벗어남: $interval" }
    }

    @Test
    fun `지수 백오프 - 시도가 늘면 대기가 커지되 상한을 넘지 않는다`() {
        val second = SupplierRetryPolicy.interval(attempt = 2, failure = failure())
        assertTrue(second in 200..600) { "400ms ±50% 구간을 벗어남: $second" }

        // 시도 횟수가 정책으로 크게 늘어도 한 번의 대기는 상한(5초)에서 멈춘다
        val far = SupplierRetryPolicy.interval(attempt = 10, failure = failure())
        assertEquals(5_000L, far)
    }

    @Test
    fun `판별 - 일시 실패만 재시도 대상이다`() {
        val predicate = RetryablePredicate()

        assertTrue(predicate.test(failure()))
        assertFalse(predicate.test(failure(retryable = false)))
        assertFalse(predicate.test(IllegalStateException("공급사 실패가 아닌 예외")))
    }
}
