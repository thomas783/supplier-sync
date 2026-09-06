package com.staysync.web

import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.validation.BindException
import org.springframework.web.HttpRequestMethodNotSupportedException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.servlet.resource.NoResourceFoundException

/**
 * 예외 → 응답 변환 일원화.
 *
 * 요청 검증은 요청 DTO 의 Bean Validation 선언(@Valid)이 수행하고, 그 위반과 바인딩 실패, 검증 이외의
 * 타입 예외([ApiException]), 프레임워크 판정(404·405), 예기치 못한 예외까지 전부 여기서 일관된
 * [ApiErrorResponse] 로 변환한다. 예외마다 핸들러를 명시적으로 나눠 어떤 실패가 어떤 응답이 되는지가
 * 메서드 목록만으로 드러나게 한다.
 */
@RestControllerAdvice
class GlobalExceptionHandler {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 검증 이외의 실패를 컨트롤러가 타입으로 던지는 경로 — 예외가 품은 상태 코드를 그대로 쓴다. */
    @ExceptionHandler(ApiException::class)
    fun handleApi(e: ApiException): ResponseEntity<ApiErrorResponse> =
        ResponseEntity.status(e.status)
            .body(ApiErrorResponse.of(e.status, e.message ?: e.status.reasonPhrase))

    /**
     * 요청 바인딩·검증 실패 — 첫 위반 하나를 사람이 읽을 수 있는 400 사유로 변환한다 (docs/API.md).
     * @Valid 위반(MethodArgumentNotValidException)이 [BindException] 을 상속하므로, 이 핸들러 하나가
     * 타입 불일치(바인딩 실패)와 누락(@NotNull)·규칙 위반(@Min 등)을 모두 받는다.
     */
    @ExceptionHandler(BindException::class)
    fun handleBindFailure(e: BindException): ResponseEntity<ApiErrorResponse> {
        // 위반이 여러 개면 우선순위(바인딩 실패 > 누락 > 규칙 위반)로 대표를 고른다 — fieldErrors 의
        // 순서는 Validator 구현의 Set 순회에 좌우되어 보장이 없으므로, 종류 우선순위를 명시적으로 강제한다
        val errors = e.bindingResult.fieldErrors
        val error = errors.firstOrNull { it.isBindingFailure }
            ?: errors.firstOrNull { it.code == "NotNull" }
            ?: errors.firstOrNull()
        val message = when {
            error == null -> "잘못된 요청입니다"
            // 타입 불일치 (예: 날짜 형식 오류, 정수 파싱 실패) — 검증 이전, 바인딩 단계의 실패
            error.isBindingFailure -> "파라미터 '${error.field}'의 값이 올바르지 않습니다: ${error.rejectedValue}"
            // 필수 파라미터 누락 — nullable 필드의 @NotNull 위반으로 도착한다
            error.code == "NotNull" -> "필수 파라미터가 누락되었습니다: '${error.field}'"
            // 그 외 선언된 규칙 위반 — 제약 애노테이션에 적힌 메시지를 그대로 쓴다
            else -> error.defaultMessage ?: "잘못된 요청입니다"
        }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(ApiErrorResponse.of(HttpStatus.BAD_REQUEST, message))
    }

    /** 존재하지 않는 경로 — 클라이언트 실수를 500 으로 뭉개지 않고 404 판정을 보존한다 (docs/API.md). */
    @ExceptionHandler(NoResourceFoundException::class)
    fun handleNotFound(e: NoResourceFoundException): ResponseEntity<ApiErrorResponse> =
        ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(ApiErrorResponse.of(HttpStatus.NOT_FOUND, "요청한 경로를 찾을 수 없습니다"))

    /** 경로는 맞지만 메서드가 다른 요청 — 405 판정을 보존한다. */
    @ExceptionHandler(HttpRequestMethodNotSupportedException::class)
    fun handleMethodNotSupported(e: HttpRequestMethodNotSupportedException): ResponseEntity<ApiErrorResponse> =
        ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
            .body(ApiErrorResponse.of(HttpStatus.METHOD_NOT_ALLOWED, "허용되지 않은 HTTP 메서드입니다"))

    /** 그 외 예기치 못한 예외 — 내부 상세는 error 로그에만 남기고 응답은 불투명하게 (docs/API.md). */
    @ExceptionHandler(Exception::class)
    fun handleUnexpected(e: Exception): ResponseEntity<ApiErrorResponse> {
        log.error("unhandled exception", e)
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(ApiErrorResponse.of(HttpStatus.INTERNAL_SERVER_ERROR, "내부 오류가 발생했습니다"))
    }
}
