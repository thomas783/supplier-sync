package com.staysync.web

import org.springframework.http.HttpStatus

/**
 * API 계층에서 클라이언트에게 상태 코드로 돌려줄 예외의 베이스.
 * 핸들러([GlobalExceptionHandler])가 이 타입을 잡아 일관된 에러 응답으로 변환한다.
 */
abstract class ApiException(val status: HttpStatus, message: String) : RuntimeException(message)

/** 400 — 잘못된 요청. 클라이언트가 고칠 수 있도록 구체적 사유를 담는다 (docs/API.md). */
class BadRequestException(message: String) : ApiException(HttpStatus.BAD_REQUEST, message)
