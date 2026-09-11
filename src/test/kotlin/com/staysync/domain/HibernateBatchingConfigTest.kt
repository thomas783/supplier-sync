package com.staysync.domain

import com.staysync.TestcontainersConfiguration
import jakarta.persistence.EntityManagerFactory
import org.hibernate.engine.spi.SessionFactoryImplementor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles

/**
 * Hibernate JDBC 배치 설정이 세션 팩토리에 실제로 바인딩되는지 고정한다.
 *
 * `spring.jpa.properties.*` 는 키가 틀리거나 relaxed binding 에 걸리면 조용히 무시되어 배치가 꺼진 채로
 * 돌 수 있다(다른 곳의 yml 무시 사고와 같은 결). 재동기화의 UPDATE 왕복을 줄이려 켠 설정이 드리프트로
 * 사라지지 않도록, 효과가 있는 값(`batch_size`·`order_updates`)의 바인딩을 검증한다.
 * INSERT 는 IDENTITY 라 배치되지 않으므로 `order_inserts` 는 검증 대상이 아니다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import(TestcontainersConfiguration::class)
class HibernateBatchingConfigTest {

    @Autowired
    lateinit var entityManagerFactory: EntityManagerFactory

    @Value("\${spring.datasource.url}")
    lateinit var datasourceUrl: String

    @Test
    fun `jdbc batch_size 와 order_updates 가 실제로 바인딩된다`() {
        val options = entityManagerFactory.unwrap(SessionFactoryImplementor::class.java).sessionFactoryOptions

        // application.yml 의 spring.jpa.properties.hibernate 값 — 조용한 드리프트를 막는 고정값
        assertEquals(50, options.jdbcBatchSize)
        assertTrue(options.isOrderUpdatesEnabled)
    }

    @Test
    fun `데이터소스 URL 에 rewriteBatchedStatements 가 켜져 있다`() {
        // batch_size 만으로는 Connector/J 가 문장별로 전송한다 — 이 파라미터가 있어야 배치가 실제 한 번의
        // 왕복으로 나간다. URL 에서 빠지면 배치가 조용히 무력화되므로 그 회귀를 막는다.
        assertTrue(
            datasourceUrl.contains("rewriteBatchedStatements=true"),
            "spring.datasource.url 에 rewriteBatchedStatements=true 가 있어야 한다: $datasourceUrl",
        )
    }
}
