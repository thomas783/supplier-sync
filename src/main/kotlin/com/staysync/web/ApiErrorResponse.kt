package com.staysync.web

import org.springframework.http.HttpStatus

/** 일관된 에러 응답 포맷 — 형태는 하나로 고정하고 필드를 즉흥적으로 늘리지 않는다 (docs/API.md). */
data class ApiErrorResponse(
    val status: Int,
    val error: String,
    val message: String,
) {
    companion object {
        fun of(status: HttpStatus, message: String) =
            ApiErrorResponse(status.value(), status.reasonPhrase, message)
    }
}
