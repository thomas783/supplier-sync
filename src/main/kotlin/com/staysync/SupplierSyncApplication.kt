package com.staysync

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.data.jpa.repository.config.EnableJpaAuditing
import java.util.TimeZone

// 감사 컬럼(created_at/updated_at)은 JPA Auditing 이 채운다 — docs/ERD.md 감사 컬럼 절
@EnableJpaAuditing
@SpringBootApplication
class SupplierSyncApplication

fun main(args: Array<String>) {
    // DATETIME 에 KST 벽시계를 저장하는 전제 — 실행 환경(로컬 KST, CI·서버 UTC)과 무관하게 코드로 고정 (docs/ERD.md)
    TimeZone.setDefault(TimeZone.getTimeZone("Asia/Seoul"))
    runApplication<SupplierSyncApplication>(*args)
}
