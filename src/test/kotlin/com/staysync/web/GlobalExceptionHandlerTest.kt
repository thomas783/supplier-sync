package com.staysync.web

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus

/**
 * 예외 → 응답 변환의 단위 검증. 프레임워크 판정(404·405)과 검증(400)은 통합·요청 테스트가 배선을
 * 증명하므로, 여기서는 내부 상세 비노출(500)과 [ApiException] 변환 두 분기를 핸들러 직접 호출로 고정한다.
 */
class GlobalExceptionHandlerTest {

    private val handler = GlobalExceptionHandler()

    @Test
    fun `500 - 예기치 못한 예외의 내부 상세는 응답에 노출되지 않는다`() {
        val secret = "boom at Internal.detail(stack-trace-and-cause)"

        val response = handler.handleUnexpected(RuntimeException(secret))

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR.value(), response.statusCode.value())
        val body = response.body!!
        assertEquals("내부 오류가 발생했습니다", body.message)
        // 원인 메시지가 어떤 필드로도 새어 나가지 않는다 (정보 유출 방지 계약, docs/API.md)
        assertFalse(body.toString().contains(secret))
    }

    @Test
    fun `ApiException - 예외가 품은 상태 코드와 메시지가 그대로 변환된다`() {
        val response = handler.handleApi(NotFoundTestException())

        assertEquals(HttpStatus.NOT_FOUND.value(), response.statusCode.value())
        assertEquals("숙소를 찾을 수 없습니다", response.body!!.message)
    }

    private class NotFoundTestException : ApiException(HttpStatus.NOT_FOUND, "숙소를 찾을 수 없습니다")
}
