package com.staysync.resilience

import com.staysync.domain.model.Supplier
import com.staysync.supplier.SupplierCallException
import io.github.resilience4j.core.IntervalFunction

/**
 * 재시도 대기의 산정 규칙 (근거는 docs/INTEGRATION.md). 시도 횟수·판별·인스턴스 선언 등 정적인
 * 값들은 yml(resilience4j.retry)이 원천이고, 이 규칙만 [SupplierRetryIntervalBiFunction] 을 통해
 * 클래스 위임으로 연결된다.
 *
 * 대기가 실패의 종류에 따라 갈리기 때문에 yml 의 스칼라 하나(wait-duration)로는 담을 수 없다:
 * 한도 초과(429/E429)는 한도 창이 보통 초 단위로 회복되므로 길게, 그 외 일시 오류·타임아웃은 순간
 * 혼잡의 회복을 노려 짧게. 기본 대기에는 지수 백오프와 ±50% 지터가 붙는다 — 백오프 곡선은 재시도
 * 1회인 지금은 잠재적 장치지만 시도 횟수가 정책으로 바뀔 수 있고, 지터는 동시 실패한 병렬 청크들이
 * 정확히 같은 시각에 재발사되어 공급사를 다시 때리는 것을 막는 현재형 장치다.
 */
object SupplierRetryPolicy {

    /** 일시 오류(5xx·타임아웃)의 기본 대기. */
    private const val TRANSIENT_BASE_MS = 200L

    /** 한도 초과(429)의 기본 대기. */
    private const val RATE_LIMIT_BASE_MS = 1_000L

    private const val BACKOFF_MULTIPLIER = 2.0
    private const val JITTER_FACTOR = 0.5

    /** 한 번의 대기 상한 — 시도 횟수가 늘어나도 백오프가 이 이상 커지지 않는다. */
    private const val MAX_INTERVAL_MS = 5_000L

    private val transientBackoff =
        IntervalFunction.ofExponentialRandomBackoff(TRANSIENT_BASE_MS, BACKOFF_MULTIPLIER, JITTER_FACTOR)
    private val rateLimitBackoff =
        IntervalFunction.ofExponentialRandomBackoff(RATE_LIMIT_BASE_MS, BACKOFF_MULTIPLIER, JITTER_FACTOR)

    /** 시도 번호와 실패를 받아 다음 대기(ms)를 정한다. */
    fun interval(attempt: Int, failure: Throwable?): Long {
        val backoff = if (failure is SupplierCallException && failure.rateLimited) rateLimitBackoff else transientBackoff
        return backoff.apply(attempt).coerceAtMost(MAX_INTERVAL_MS)
    }
}

/**
 * 재시도가 적용되는 원격 경로 — 인스턴스 이름의 파생 규칙만 담는다. 경로별 시도 예산은 yml 의
 * 설정(config) `search`/`sync` 가 원천이고, 이름이 어긋나면 기동 시 인스턴스가 생기지 않아 드러난다.
 *
 * 인스턴스 이름은 (공급사)-(경로) 대칭 표기다 — 지표에 그대로 실리므로 자기 설명적이어야 한다.
 * (서킷 인스턴스는 경로 구분 없이 공급사 단위 `A`/`B` — 현재 서킷은 검색 경로에만 적용되기 때문이다)
 */
enum class RetryPath(val configName: String) {

    /** 검색(재고·요금): 고객이 기다리는 응답 — 시도 예산이 짧다 (yml `search`). */
    SEARCH("search"),

    /** 동기화(숙소 목록): 지연에 둔감한 배치 — 시도 예산이 더 많다 (yml `sync`). */
    SYNC("sync"),
    ;

    fun instanceName(supplier: Supplier): String = "${supplier.name}-$configName"
}
