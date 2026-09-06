package com.staysync.web

import org.springframework.http.HttpStatus

/**
 * API 계층에서 클라이언트에게 상태 코드로 돌려줄 예외의 베이스.
 * 핸들러([GlobalExceptionHandler])가 이 타입을 잡아 일관된 에러 응답으로 변환한다.
 *
 * 요청 파라미터 검증은 선언(@Valid)이 담당하므로 여기를 거치지 않는다 — 이 계층은 검증 이외의
 * 실패(예: 향후 단건 조회의 404 같은 업무 판정)를 컨트롤러가 타입으로 던지는 표준 경로다.
 */
abstract class ApiException(val status: HttpStatus, message: String) : RuntimeException(message)
