package com.staysync.web

import com.staysync.sync.PropertySyncService
import com.staysync.sync.SupplierSyncResult
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 매핑 수동 재동기화 트리거 (docs/API.md 운영 엔드포인트).
 * 숙소 목록이 바뀌었을 때 다음 배치를 기다리지 않고 즉시 갱신하기 위한 운영용이다.
 */
@RestController
class PropertySyncController(
    private val propertySyncService: PropertySyncService,
) {

    @PostMapping("/internal/properties/sync")
    fun sync(): List<SupplierSyncResult> = propertySyncService.syncAll()
}
