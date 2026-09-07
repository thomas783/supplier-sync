package com.staysync.web

import com.staysync.sync.PropertySyncService
import com.staysync.sync.SupplierSyncResult
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 매핑 수동 재동기화 트리거 (docs/API.md 운영 엔드포인트).
 * 숙소 목록이 바뀌었을 때 다음 배치를 기다리지 않고 즉시 갱신하기 위한 운영용이다.
 */
@RestController
@Tag(name = "운영", description = "호환성 관리 대상이 아닌 내부 운영 도구 (/internal)")
class PropertySyncController(
    private val propertySyncService: PropertySyncService,
) {

    @Operation(
        summary = "매핑 수동 재동기화",
        description = "공급사 숙소 목록을 다시 받아 내부 매핑을 갱신한다. 멱등하며, 계약 밖 레코드는 " +
            "건너뛰고 skipped 로 집계된다.",
    )
    @PostMapping("/internal/properties/sync")
    fun sync(): List<SupplierSyncResult> = propertySyncService.syncAll()
}
