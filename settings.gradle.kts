// 플러그인 버전의 단일 원천 — 루트(본 앱)와 mock-supplier 모듈이 같은 버전을 쓴다
pluginManagement {
    plugins {
        kotlin("jvm") version "2.1.20"
        kotlin("plugin.spring") version "2.1.20"
        kotlin("plugin.jpa") version "2.1.20"
        id("org.springframework.boot") version "3.4.13"
        id("io.spring.dependency-management") version "1.1.7"
    }
}

rootProject.name = "supplier-sync"

// Mock 공급사 서버 — 본 앱 아티팩트·의존성과 분리된 별도 모듈 (docs/ARCHITECTURE.md)
include("mock-supplier")
