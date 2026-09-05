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

