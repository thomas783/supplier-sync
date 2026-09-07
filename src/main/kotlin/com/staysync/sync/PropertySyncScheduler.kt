package com.staysync.sync

import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * 주기 배치 매핑 동기화.
 *
 * 숙소 목록은 정적 콘텐츠라 하루 1회면 충분하고, 즉시 반영은 수동 트리거가 담당한다.
 * 주기는 application.yml 의 `sync.property-cron` 이 유일한 원천이다 (기본값 없음 — 누락 시 기동 실패).
 */
@Component
class PropertySyncScheduler(
    private val propertySyncService: PropertySyncService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(cron = "\${sync.property-cron}")
    fun scheduledSync() {
        log.info("scheduled property sync")
        // syncAll 은 실패를 예외가 아니라 공급사별 결과(ok=false)로 흡수하므로, 배치 경로가 결과를
        // 버리면 전 공급사 실패도 조용히 지나간다 — 실패 공급사를 집계해 error 로 드러낸다.
        val results = propertySyncService.syncAll()
        val failed = results.filter { !it.ok }
        if (failed.isNotEmpty()) {
            log.error(
                "scheduled property sync had failures: failed={} total={} suppliers={}",
                failed.size, results.size, failed.map { it.supplier },
            )
        }
    }
}
