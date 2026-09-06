package com.staysync.web

import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException

/**
 * 예외 → 응답 변환 일원화.
 *
 * 컨트롤러는 검증 실패 시 [ApiException] 을 던지기만 하면 되고, 요청 바인딩 단계의 실패
 * (파라미터 누락·타입 불일치)와 예기치 못한 예외까지 여기서 일관된 [ApiErrorResponse] 로 변환한다.
 */
@RestControllerAdvice
class GlobalExceptionHandler {
    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(ApiException::class)
    fun handleApi(e: ApiException): ResponseEntity<ApiErrorResponse> =
        ResponseEntity.status(e.status)
            .body(ApiErrorResponse.of(e.status, e.message ?: e.status.reasonPhrase))

    /** 필수 파라미터 누락 (예: checkIn 미전달). */
    @ExceptionHandler(MissingServletRequestParameterException::class)
    fun handleMissingParam(e: MissingServletRequestParameterException): ResponseEntity<ApiErrorResponse> =
        badRequest("필수 파라미터가 누락되었습니다: '${e.parameterName}'")

    /** 파라미터 타입 불일치 (예: 날짜 형식 오류, 정수 파싱 실패). */
    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    fun handleTypeMismatch(e: MethodArgumentTypeMismatchException): ResponseEntity<ApiErrorResponse> =
        badRequest("파라미터 '${e.name}'의 값이 올바르지 않습니다: ${e.value}")

    /** 그 외 예기치 못한 예외 — 내부 상세는 error 로그에만 남기고 응답은 불투명하게 (docs/API.md). */
    @ExceptionHandler(Exception::class)
    fun handleUnexpected(e: Exception): ResponseEntity<ApiErrorResponse> {
        log.error("unhandled exception", e)
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(ApiErrorResponse.of(HttpStatus.INTERNAL_SERVER_ERROR, "내부 오류가 발생했습니다"))
    }

    private fun badRequest(message: String): ResponseEntity<ApiErrorResponse> =
        ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(ApiErrorResponse.of(HttpStatus.BAD_REQUEST, message))
}
