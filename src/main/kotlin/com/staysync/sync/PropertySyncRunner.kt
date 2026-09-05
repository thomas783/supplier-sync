package com.staysync.sync

import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.stereotype.Component

/**
 * 기동 시 1회 매핑 동기화.
 *
 * 검색은 매핑을 전제로 하므로, 첫 검색 전에 최소 한 번은 매핑이 채워져 있어야 한다.
 * 단, 동기화 실패가 앱 기동을 막지 않는다 — 매핑이 비어도 앱은 뜨고, 이후 배치/수동 트리거로 채운다.
 */
@Component
class PropertySyncRunner(
    private val propertySyncService: PropertySyncService,
) : ApplicationRunner {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun run(args: ApplicationArguments) {
        log.info("initial property sync on startup")
        try {
            propertySyncService.syncAll()
        } catch (e: Exception) {
            // 공급사 장애는 서비스가 이미 격리하므로 여기 오는 것은 DB 오류 등 인프라 예외다.
            // Exception 만 잡는다 — Error 계열(JVM 치명 상태)까지 삼켜 기동을 강행하지 않는다.
            log.error("initial property sync failed; app starts with existing/empty mappings", e)
        }
    }
}
