import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Mock 공급사 서버 — 본 앱과 분리된 모듈이라 웹 스타터만 의존한다.
// 본 앱 아티팩트에 Mock 코드가 실리지 않고, Mock 기동에 JPA·DB 가 끼어들 여지도 없다.
plugins {
    kotlin("jvm")
    kotlin("plugin.spring")
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}

group = "com.staysync"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict")
        jvmTarget = JvmTarget.JVM_21
    }
}

// 수동 시연용 실행: ./gradlew :mock-supplier:bootRun (9090)
tasks.bootRun {
    args("--server.port=9090")
}
