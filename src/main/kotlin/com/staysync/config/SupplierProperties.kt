package com.staysync.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * 공급사 연동 설정 — 값은 application.yml 의 supplier 절이 유일한 원천이다.
 * 코드 기본값을 두지 않아, 설정이 누락되면 기동 시점에 바인딩 실패로 드러난다.
 * 타임아웃 값(연결 1초 / 응답 5초)과 동시 호출 상한의 근거는 docs/INTEGRATION.md 와 yml 주석 참고.
 */
@ConfigurationProperties(prefix = "supplier")
data class SupplierProperties(
    val connectTimeoutMs: Int,
    val responseTimeoutMs: Long,
    val maxConcurrentCalls: Int,
    val a: Endpoint,
    val b: Endpoint,
) {
    data class Endpoint(
        val baseUrl: String,
        val apiKey: String,
    )
}
