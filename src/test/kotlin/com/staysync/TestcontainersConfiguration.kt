package com.staysync

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.testcontainers.containers.MySQLContainer
import org.testcontainers.utility.DockerImageName

// MySQLContainer 는 자기 참조 제네릭(SELF)이라 Kotlin 에서 타입 인자 없이 생성할 수 없어 서브클래스로 고정한다
private class KMySQLContainer(image: DockerImageName) : MySQLContainer<KMySQLContainer>(image)

/**
 * 테스트가 스스로 MySQL 8.4 컨테이너를 띄우고 정리한다 — 로컬 compose 컨테이너와 무관하게 재현 가능.
 * @ServiceConnection 이 datasource 접속 정보를 컨테이너에서 자동 구성한다.
 */
@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    fun mysqlContainer(): MySQLContainer<*> =
        KMySQLContainer(DockerImageName.parse("mysql:8.4"))
            // 운영과 동일한 3계층 KST — 컨테이너 시계와 JDBC 연결 시간대 (docs/ERD.md)
            .withEnv("TZ", "Asia/Seoul")
            .withUrlParam("connectionTimeZone", "Asia/Seoul")
}
