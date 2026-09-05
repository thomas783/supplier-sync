import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// 플러그인 버전은 settings.gradle.kts 의 pluginManagement 가 단일 원천
plugins {
    kotlin("jvm")
    // Spring 빈(@Component/@Configuration/@Transactional 등)을 open 처리 → AOP/트랜잭션 프록시 생성 가능
    kotlin("plugin.spring")
    // JPA(@Entity/@MappedSuperclass/@Embeddable)에 no-arg 생성자 생성 → Hibernate 인스턴스화
    kotlin("plugin.jpa")
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

    // MySQL 드라이버 — 버전은 Boot BOM 관리
    runtimeOnly("com.mysql:mysql-connector-j")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("io.projectreactor:reactor-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    // 테스트가 MySQL 8.4 컨테이너를 스스로 구동 — @ServiceConnection 이 datasource 를 자동 구성
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:mysql")
    // 어댑터 HTTP 계약 검증용 목 서버 — 버전은 Boot BOM 관리 밖이라 명시 고정
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
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
    // 테스트 JVM 도 KST 고정 — main() 의 TimeZone.setDefault 는 @SpringBootTest 실행 경로에 없다
    systemProperty("user.timezone", "Asia/Seoul")
}
