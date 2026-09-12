package com.staysync.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * 공급사 연동 설정 — 값은 application.yml 의 supplier 절이 유일한 원천이다.
 * 코드 기본값을 두지 않아, 설정이 누락되면 기동 시점에 바인딩 실패로 드러난다.
 * 응답 타임아웃은 경로별로 다르다 — 검색(고객 대기)은 빠듯하게, 동기화(배치)는 관대하게.
 * 값들의 근거는 docs/INTEGRATION.md 와 yml 주석 참고.
 */
@ConfigurationProperties(prefix = "supplier")
data class SupplierProperties(
    val connectTimeoutMs: Int,
    val searchResponseTimeoutMs: Long,
    val syncResponseTimeoutMs: Long,
    // 검색 전체(e2e) 데드라인 — 느린 공급사가 전체 응답을 무한정 끌지 않도록 하는 상한. 이 시점에 도착한
    // 결과는 유지하고 미완 공급사는 TIMEOUT 으로 채운다. 부등식: searchResponseTimeoutMs < searchDeadlineMs.
    // (공급사별 동시성 상한은 resilience4j.bulkhead 가 단일 원천 — 검색 팬아웃이 그 값을 읽어 큐잉한다.)
    val searchDeadlineMs: Long,
    // 공급사별 커넥션 풀 상한. bulkhead 상한보다 커야(부등식) 전송 계층이 숨은 병목이 되지 않는다.
    val maxConnections: Int,
    // 풀에서 커넥션을 얻기까지 대기 상한. 응답 타임아웃보다 짧아야(부등식) 풀 고갈이 엉뚱하게 늦은
    // 타임아웃으로 나타나지 않는다.
    val pendingAcquireTimeoutMs: Long,
    val a: Endpoint,
    val b: Endpoint,
) {
    data class Endpoint(
        val baseUrl: String,
        val apiKey: String,
    )
}
