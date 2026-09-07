package com.staysync.supplier

import com.staysync.config.SupplierProperties
import com.staysync.supplier.a.SupplierAClient
import com.staysync.supplier.b.SupplierBClient
import mocksupplier.MockSupplierApplication
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono
import reactor.netty.http.client.HttpClient
import java.time.Duration
import java.time.LocalDate

/**
 * 실행형 Mock 공급사 서버와 **실제 HTTP 로 통신**하는 통합 테스트.
 *
 * 테스트가 Mock 애플리케이션을 임의 포트로 직접 띄우므로 CI 와 일반 `./gradlew test` 에서도 항상
 * 실행된다(포트 충돌 없음 — 수동 시연용 `bootRunMock`(9090)과 공존 가능). 어댑터의 회귀 고정은
 * MockWebServer 테스트가 담당하고, 여기서는 "실행형 Mock 의 픽스처가 어댑터를 실제로 통과한다"를
 * 확인한다 — Mock 픽스처와 어댑터 DTO 가 어긋나는 드리프트를 이 테스트가 잡는다.
 */
class MockSupplierIntegrationTest {

    private val query = StayProductQuery(
        propertyCodes = listOf("A-10023", "A-10044", "B77120"),
        checkIn = LocalDate.of(2026, 9, 1),
        checkOut = LocalDate.of(2026, 9, 4),
        adults = 2,
        children = 0,
    )

    @Test
    fun `A - 숙소 목록과 재고 요금이 실행형 Mock 을 통과한다`() {
        val client = SupplierAClient(webClient(), properties())

        val properties = client.fetchProperties()
        assertEquals(2, properties.size)

        val products = client.fetchStayProducts(query).block()!!
        val riverside = products.first { it.supplierPropertyCode == "A-10023" }
        assertEquals(429000, riverside.grossTotalAmount) // (120000+12000)+(150000+15000)+(120000+12000)
    }

    @Test
    fun `B - 숙소 목록과 재고 요금이 실행형 Mock 을 통과한다`() {
        val client = SupplierBClient(webClient(), properties())

        val properties = client.fetchProperties()
        assertEquals("B77120", properties.single().supplierPropertyCode)

        val product = client.fetchStayProducts(query).block()!!.single()
        assertEquals(452000, product.grossTotalAmount)
    }

    @Test
    fun `모드 전환 - 알 수 없는 모드는 400 으로 거부된다`() {
        assertEquals(400, postModeStatus("/control/a/mode?value=oops"))
    }

    @Test
    fun `모드 전환 - 알 수 없는 공급사는 400 으로 거부된다`() {
        assertEquals(400, postModeStatus("/control/aa/mode?value=error"))
    }

    // 오타가 조용히 normal 로 동작하는 것을 막는 가드의 회귀 방지 (Mock 컨트롤러의 화이트리스트 검증)
    private fun postModeStatus(uri: String): Int = webClient().post()
        .uri(uri)
        .exchangeToMono { response -> Mono.just(response.statusCode().value()) }
        .block()!!

    private fun properties() = SupplierProperties(
        connectTimeoutMs = 1000,
        searchResponseTimeoutMs = 5000,
        syncResponseTimeoutMs = 5000,
        maxConcurrentCalls = 16,
        a = SupplierProperties.Endpoint(baseUrl = "unused", apiKey = "unused"),
        b = SupplierProperties.Endpoint(baseUrl = "unused", apiKey = "unused"),
    )

    private fun webClient(): WebClient = WebClient.builder()
        .baseUrl("http://localhost:$mockPort")
        .defaultHeader("X-Api-Key", "mock-api-key")
        .clientConnector(
            ReactorClientHttpConnector(HttpClient.create().responseTimeout(Duration.ofSeconds(5))),
        )
        .build()

    companion object {
        private lateinit var mockContext: ConfigurableApplicationContext
        private var mockPort: Int = 0

        @JvmStatic
        @BeforeAll
        fun startMock() {
            // 임의 포트(--server.port=0)로 띄워 CI·로컬 어디서든 충돌 없이 항상 실행된다.
            // Mock 모듈 자체는 웹 의존성뿐이지만, 이 테스트는 본 모듈 클래스패스(JPA 포함)에서 돌므로
            // 목 앱 기동에 JPA 자동 구성이 끼어들지 않게 여기서만 제외한다.
            mockContext = SpringApplicationBuilder(MockSupplierApplication::class.java)
                .properties(
                    "spring.autoconfigure.exclude=" +
                        "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration," +
                        "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration," +
                        "org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration",
                )
                .run("--server.port=0")
            mockPort = (mockContext as ServletWebServerApplicationContext).webServer.port
        }

        @JvmStatic
        @AfterAll
        fun stopMock() {
            mockContext.close()
        }
    }
}
