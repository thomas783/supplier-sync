package com.staysync

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import java.util.TimeZone

@SpringBootApplication
class SupplierSyncApplication

fun main(args: Array<String>) {
    // DATETIME 에 KST 벽시계를 저장하는 전제 — 실행 환경(로컬 KST, CI·서버 UTC)과 무관하게 코드로 고정 (docs/ERD.md)
    TimeZone.setDefault(TimeZone.getTimeZone("Asia/Seoul"))
    runApplication<SupplierSyncApplication>(*args)
}
