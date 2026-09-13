package com.staysync.resilience

import com.staysync.TestcontainersConfiguration
import com.staysync.config.SupplierProperties
import com.staysync.domain.model.Supplier
import io.github.resilience4j.bulkhead.BulkheadRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles

/**
 * 회복탄력성·팬아웃 설정이 "정렬된 시스템"인지 고정한다 (docs/INTEGRATION.md).
 *
 * 값 하나가 짝과 어긋나면 다른 값이 조용히 숨은 병목이 되므로(예: 풀이 bulkhead 보다 작으면 전송 계층이
 * 먼저 막힌다), 서로 맞물린 부등식을 테스트로 못박는다. 동시성 상한은 bulkhead 설정이 단일 원천이라 그
 * 값을 읽어 검증한다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration::class)
class SupplierResilienceConfigTest {

    @Autowired
    lateinit var properties: SupplierProperties

    @Autowired
    lateinit var bulkheadRegistry: BulkheadRegistry

    @Autowired
    lateinit var resilience: SupplierResilience

    @Test
    fun `searchConcurrency 는 bulkhead 설정을 단일 원천으로 읽는다`() {
        // 검색 팬아웃이 이 값으로 큐잉하므로, bulkhead 설정과 어긋나지 않음을 고정한다(단일 원천 배선 검증)
        Supplier.entries.forEach { supplier ->
            assertEquals(
                bulkheadRegistry.bulkhead(supplier.name).bulkheadConfig.maxConcurrentCalls,
                resilience.searchConcurrency(supplier),
                "searchConcurrency(${supplier.name}) 가 bulkhead 설정값과 다르다",
            )
        }
    }

    @Test
    fun `정렬 부등식 - 타임아웃·동시성·풀이 서로 맞물린다`() {
        // 공급사별 동시성 상한(bulkhead) — 단일 원천이라 여기서 읽는다. 공급사 무관하게 같은 default 설정
        val concurrency = bulkheadRegistry.bulkhead(Supplier.A.name).bulkheadConfig.maxConcurrentCalls
        assertEquals(
            concurrency,
            bulkheadRegistry.bulkhead(Supplier.B.name).bulkheadConfig.maxConcurrentCalls,
            "공급사별 bulkhead 상한이 같은 default 로 설정돼야 한다",
        )
        assertTrue(
            properties.connectTimeoutMs < properties.searchResponseTimeoutMs,
            "connect-timeout < search-response-timeout 이어야 한다",
        )
        assertTrue(
            properties.searchResponseTimeoutMs < properties.searchDeadlineMs,
            "search-response-timeout < search-deadline(e2e) 이어야 최소 한 번의 시도가 끝난다",
        )
        assertTrue(
            concurrency < properties.maxConnections,
            "bulkhead 동시성 < max-connections(pool) 이어야 전송 계층이 병목이 안 된다",
        )
        assertTrue(
            properties.pendingAcquireTimeoutMs < properties.searchResponseTimeoutMs,
            "pending-acquire-timeout < search-response-timeout 이어야 풀 고갈이 늦은 타임아웃으로 위장되지 않는다",
        )
    }
}
