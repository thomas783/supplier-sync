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
        propertySyncService.syncAll()
    }
}
