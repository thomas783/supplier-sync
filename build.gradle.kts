import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.1.20"
    // Spring 빈(@Component/@Configuration/@Transactional 등)을 open 처리 → AOP/트랜잭션 프록시 생성 가능
    kotlin("plugin.spring") version "2.1.20"
    // JPA(@Entity/@MappedSuperclass/@Embeddable)에 no-arg 생성자 생성 → Hibernate 인스턴스화
    kotlin("plugin.jpa") version "2.1.20"
    id("org.springframework.boot") version "3.4.13"
    id("io.spring.dependency-management") version "1.1.7"
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
    // Web (MVC) — 통합 검색 API 노출
    implementation("org.springframework.boot:spring-boot-starter-web")
    // WebFlux — WebClient(외부 Supplier 병렬 호출)만 사용. 웹 서버는 MVC(Tomcat) 유지
    implementation("org.springframework.boot:spring-boot-starter-webflux")
    // 영속화 — 공급사 코드 ↔ 내부 식별자 매핑 저장
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    // 요청 검증
    implementation("org.springframework.boot:spring-boot-starter-validation")
    // 운영 지표 (헬스체크 등)
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("org.jetbrains.kotlin:kotlin-reflect")

    runtimeOnly("com.h2database:h2")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("io.projectreactor:reactor-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict")
        jvmTarget = JvmTarget.JVM_21
    }
}

// JPA 엔티티 클래스를 open 처리 → Hibernate 가 LAZY 로딩 프록시(엔티티 subclass)를 만들 수 있게 함.
// (no-arg 생성자는 위 plugin.jpa 가, 클래스 open 은 이 블록이 담당 — 역할이 다르다)
allOpen {
    annotation("jakarta.persistence.Entity")
    annotation("jakarta.persistence.MappedSuperclass")
    annotation("jakarta.persistence.Embeddable")
}

tasks.withType<Test> {
    useJUnitPlatform()
}
