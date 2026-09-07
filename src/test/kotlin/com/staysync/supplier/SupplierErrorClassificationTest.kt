package com.staysync.supplier

import com.staysync.domain.model.Supplier
import io.netty.channel.ConnectTimeoutException
import io.netty.handler.timeout.ReadTimeoutException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.core.codec.DecodingException
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.web.reactive.function.client.WebClientResponseException

/**
 * 전송 계층 실패 분류([toSupplierError])의 함수 수준 검증.
 *
 * 응답 타임아웃은 어댑터 테스트가 MockWebServer 의 무응답으로 실제 재현하지만, 연결 타임아웃은
 * 목 서버로 재현하기 느리고 불안정하다(비라우팅 주소 의존 등). 그래서 연결 계열 분류는 예외 객체를
 * 직접 넣어 함수 수준에서 회귀를 막는다.
 */
class SupplierErrorClassificationTest {

    @Test
    fun `연결 타임아웃도 무응답 계열로 분류되고 재시도 가능이다`() {
        val ex = toSupplierError(Supplier.A, "/a/v1/availability", ConnectTimeoutException("connection timed out"))

        assertTrue(ex.reason.contains("timeout"))
        assertTrue(ex.retryable)
    }

    @Test
    fun `응답 타임아웃은 원인 예외로 감싸여 와도 무응답 계열로 분류된다`() {
        val wrapped = RuntimeException(ReadTimeoutException.INSTANCE)

        val ex = toSupplierError(Supplier.B, "/b/api/search", wrapped)

        assertTrue(ex.reason.contains("timeout"))
        assertTrue(ex.retryable)
    }

    @Test
    fun `원인 불명 예외는 보수적으로 재시도 불가로 분류된다`() {
        val ex = toSupplierError(Supplier.A, "/a/v1/hotels", RuntimeException("boom"))

        assertTrue(ex.reason.contains("call failed"))
        assertEquals(false, ex.retryable)
    }

    @Test
    fun `429 - 재시도 가능하되 한도 초과로 분류된다 - 대기를 길게 가져가는 근거`() {
        val ex = toSupplierError(Supplier.A, "/a/v1/availability", http(429))

        assertTrue(ex.retryable)
        assertTrue(ex.rateLimited)
    }

    @Test
    fun `503 - 재시도 가능하지만 한도 초과는 아니다`() {
        val ex = toSupplierError(Supplier.A, "/a/v1/availability", http(503))

        assertTrue(ex.retryable)
        assertFalse(ex.rateLimited)
    }

    @Test
    fun `500 - 일시성의 약속은 없어도 재시도 대상이다 - 인스턴스 순단 회복 기대`() {
        assertTrue(toSupplierError(Supplier.A, "/a/v1/availability", http(500)).retryable)
    }

    @Test
    fun `501 - 결정적 5xx 는 재시도하지 않는다`() {
        assertFalse(toSupplierError(Supplier.A, "/a/v1/availability", http(501)).retryable)
    }

    @Test
    fun `502 504 - 계약 밖 전송 경로 코드도 재시도 대상이다 - 앞단 인프라 순단`() {
        // RETRYABLE_STATUSES 의 근거 있는 경계값 — 집합에서 빠지는 회귀를 막는다
        assertTrue(toSupplierError(Supplier.A, "/a/v1/availability", http(502)).retryable)
        assertTrue(toSupplierError(Supplier.A, "/a/v1/availability", http(504)).retryable)
    }

    @Test
    fun `400 401 - 잘못된 요청·인증 실패는 재시도하지 않는다`() {
        assertFalse(toSupplierError(Supplier.A, "/a/v1/availability", http(400)).retryable)
        assertFalse(toSupplierError(Supplier.A, "/a/v1/availability", http(401)).retryable)
    }

    @Test
    fun `역직렬화 실패는 재시도 불가이자 별도 decode 신호로 분류된다`() {
        val ex = toSupplierError(Supplier.A, "/a/v1/availability", DecodingException("cannot decode"))

        assertFalse(ex.retryable)
        assertTrue(ex.decodeError)
        assertTrue(ex.reason.contains("decode"))
    }

    @Test
    fun `역직렬화 실패가 원인 예외로 감싸여 와도 decode 로 분류된다`() {
        val ex = toSupplierError(Supplier.B, "/b/api/search", RuntimeException(DecodingException("bad json")))

        assertTrue(ex.decodeError)
    }

    private fun http(status: Int): WebClientResponseException = WebClientResponseException.create(
        status, HttpStatus.valueOf(status).reasonPhrase, HttpHeaders(), ByteArray(0), null,
    )
}
