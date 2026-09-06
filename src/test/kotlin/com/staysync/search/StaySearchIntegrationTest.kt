package com.staysync.search

import com.staysync.TestcontainersConfiguration
import com.staysync.support.MockSupplierResponses
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.hamcrest.Matchers.contains
import org.hamcrest.Matchers.containsInAnyOrder
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.everyItem
import org.hamcrest.Matchers.`is`
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.concurrent.atomic.AtomicReference

/**
 * 통합 검색의 전체 흐름 테스트 — 기동 시 매핑 동기화 → 병렬 조회 → 정규화 → 병합 → 웹 투영을
 * 실제 HTTP 왕복(공급사별 MockWebServer)과 실제 DB(Testcontainers MySQL)로 검증한다.
 *
 * 공급사별 재고·요금 응답 모드(normal/error/no-response)를 토글해 부분 실패 견고성을 검증한다.
 * 숙소 목록은 항상 정상 응답하므로 매핑은 기동 시 1회 동기화로 채워진다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration::class)
@AutoConfigureMockMvc
class StaySearchIntegrationTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @BeforeEach
    fun resetModes() {
        aMode.set("normal")
        bMode.set("normal")
    }

    private fun search(checkIn: String = "2026-09-01", checkOut: String = "2026-09-04") = mockMvc.perform(
        get("/api/v1/stays/search")
            .param("checkIn", checkIn)
            .param("checkOut", checkOut)
            .param("adults", "2")
            .param("children", "0"),
    )

    @Test
    fun `정상 - 두 공급사 결과를 병합하고 확정 매진도 노출한다`() {
        search()
            .andExpect(status().isOk)
            // A Riverside(가능) + A Namsan(확정 매진) + B Riverside(가능)
            .andExpect(jsonPath("$.stayProducts.length()").value(3))
            .andExpect(jsonPath("$.stayProducts[*].supplier", containsInAnyOrder("A", "A", "B")))
            // A Riverside: gross 총액 = (120000+12000)+(150000+15000)+(120000+12000), 평균은 3박 내림
            .andExpect(riverside("price.totalAmount", contains(429000)))
            .andExpect(riverside("price.averageNightlyAmount", contains(143000)))
            .andExpect(riverside("availability.availableRooms", contains(1))) // 병목 최소값: [3,1,5] → 1
            // Namsan 09-02 재고 0 → 제외가 아니라 확정 매진으로 노출된다
            .andExpect(namsan("availability.isAvailable", contains(false)))
            .andExpect(namsan("availability.availableRooms", contains(0)))
            // 조식 포함 여부가 가격 조건 차이로 함께 드러난다
            .andExpect(jsonPath("$.stayProducts[?(@.supplier == 'B')].breakfastIncluded", contains(true)))
            .andExpect(jsonPath("$.errors.length()").value(0))
    }

    @Test
    fun `부분 실패 - A 가 HTTP 503 이면 B 결과만 내려가고 errors 에 A 가 남는다`() {
        aMode.set("error")

        search()
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.stayProducts.length()").value(1))
            .andExpect(jsonPath("$.stayProducts[0].supplier").value("B"))
            .andExpect(jsonPath("$.errors.length()").value(1))
            .andExpect(jsonPath("$.errors[0].supplier").value("A"))
            .andExpect(jsonPath("$.errors[0].reason", containsString("503")))
    }

    @Test
    fun `실패 판정 통일 - B 가 HTTP 200 + resultCode E503 이어도 실패로 판정된다`() {
        bMode.set("error")

        search()
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.stayProducts.length()").value(2))
            .andExpect(jsonPath("$.stayProducts[*].supplier", everyItem(`is`("A"))))
            .andExpect(jsonPath("$.errors.length()").value(1))
            .andExpect(jsonPath("$.errors[0].supplier").value("B"))
            .andExpect(jsonPath("$.errors[0].reason", containsString("E503")))
    }

    @Test
    fun `무응답 - A 가 응답하지 않으면 타임아웃으로 끊고 B 결과만 내려간다`() {
        aMode.set("no-response")

        search()
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.stayProducts.length()").value(1))
            .andExpect(jsonPath("$.stayProducts[0].supplier").value("B"))
            .andExpect(jsonPath("$.errors[0].supplier").value("A"))
            .andExpect(jsonPath("$.errors[0].reason", containsString("timeout")))
    }

    @Test
    fun `전 공급사 실패 - 그래도 200 이고 성공분 없이 errors 만 전원 기록된다`() {
        aMode.set("error")
        bMode.set("error")

        search()
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.stayProducts.length()").value(0))
            .andExpect(jsonPath("$.errors.length()").value(2))
    }

    @Test
    fun `엄격 판정 - 재고 데이터가 없는 날짜가 끼면 미확정으로 응답에서 제외된다`() {
        // 픽스처는 09-01~03 만 주므로 09-04 가 낀 4박 요청은 전 상품 미확정 → 매진 단정 없이 빈 결과
        search(checkOut = "2026-09-05")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.stayProducts.length()").value(0))
            .andExpect(jsonPath("$.errors.length()").value(0))
    }

    @Test
    fun `검증 실패 - checkOut 이 checkIn 이하면 400 일관 에러`() {
        search(checkIn = "2026-09-04", checkOut = "2026-09-01")
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.status").value(400))
            .andExpect(jsonPath("$.message").value("checkOut은 checkIn보다 뒤여야 합니다"))
    }

    @Test
    fun `검증 실패 - 성인 0명은 400 으로 거부된다`() {
        mockMvc.perform(
            get("/api/v1/stays/search")
                .param("checkIn", "2026-09-01")
                .param("checkOut", "2026-09-04")
                .param("adults", "0"),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.message").value("adults는 1 이상이어야 합니다"))
    }

    @Test
    fun `검증 실패 - 음수 children 은 400 으로 거부된다`() {
        mockMvc.perform(
            get("/api/v1/stays/search")
                .param("checkIn", "2026-09-01")
                .param("checkOut", "2026-09-04")
                .param("adults", "2")
                .param("children", "-1"),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.message").value("children은 0 이상이어야 합니다"))
    }

    @Test
    fun `children 생략 - 기본값 0 으로 정상 동작한다`() {
        mockMvc.perform(
            get("/api/v1/stays/search")
                .param("checkIn", "2026-09-01")
                .param("checkOut", "2026-09-04")
                .param("adults", "2"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.stayProducts.length()").value(3))
    }

    @Test
    fun `여러 필수 파라미터 동시 누락 - 어떤 필드든 누락 사유로 응답한다`() {
        // 다중 위반의 대표 선택은 종류 우선순위로만 보장된다 — 같은 종류 안에서 어느 필드가 뽑힐지는
        // 비결정적이므로 사유의 종류(누락)만 고정한다
        mockMvc.perform(get("/api/v1/stays/search"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.message", containsString("필수 파라미터가 누락되었습니다")))
    }

    @Test
    fun `필수 파라미터 누락 - 400 일관 에러`() {
        mockMvc.perform(
            get("/api/v1/stays/search")
                .param("checkIn", "2026-09-01")
                .param("checkOut", "2026-09-04"),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.status").value(400))
            .andExpect(jsonPath("$.message", containsString("adults")))
    }

    @Test
    fun `잘못된 날짜 형식 - 400 일관 에러`() {
        search(checkIn = "not-a-date")
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.status").value(400))
            .andExpect(jsonPath("$.message", containsString("checkIn")))
    }

    // 병합 순서는 청크 완료 순이라 비결정적 — 인덱스 대신 술어로 대상 상품을 찾는다
    private fun riverside(field: String, matcher: org.hamcrest.Matcher<*>) =
        jsonPath("$.stayProducts[?(@.supplier == 'A' && @.property.name == 'Riverside Hotel Seoul')].$field", matcher)

    private fun namsan(field: String, matcher: org.hamcrest.Matcher<*>) =
        jsonPath("$.stayProducts[?(@.property.name == 'Namsan Garden Stay')].$field", matcher)

    companion object {
        private val serverA = MockWebServer()
        private val serverB = MockWebServer()
        private val aMode = AtomicReference("normal")
        private val bMode = AtomicReference("normal")

        private fun json(body: String, code: Int = 200) =
            MockResponse().setResponseCode(code).setBody(body).addHeader("Content-Type", "application/json")

        private fun supplierDispatcher(
            propertiesPath: String,
            propertiesBody: String,
            stayProductsPath: String,
            stayProductsBody: String,
            errorResponse: () -> MockResponse,
            mode: AtomicReference<String>,
        ) = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: ""
                return when {
                    path.startsWith(propertiesPath) -> json(propertiesBody)
                    path.startsWith(stayProductsPath) -> when (mode.get()) {
                        "error" -> errorResponse()
                        "no-response" -> MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
                        else -> json(stayProductsBody)
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            serverA.dispatcher = supplierDispatcher(
                propertiesPath = "/a/v1/hotels",
                propertiesBody = MockSupplierResponses.A_HOTELS,
                stayProductsPath = "/a/v1/availability",
                stayProductsBody = MockSupplierResponses.A_AVAILABILITY,
                errorResponse = { json(MockSupplierResponses.A_ERROR, code = 503) }, // A 는 HTTP 상태로 실패
                mode = aMode,
            )
            serverB.dispatcher = supplierDispatcher(
                propertiesPath = "/b/api/properties",
                propertiesBody = MockSupplierResponses.B_PROPERTIES,
                stayProductsPath = "/b/api/search",
                stayProductsBody = MockSupplierResponses.B_SEARCH,
                errorResponse = { json(MockSupplierResponses.B_ERROR) }, // B 는 HTTP 200 + resultCode 로 실패
                mode = bMode,
            )
            serverA.start()
            serverB.start()
            registry.add("supplier.a.base-url") { "http://localhost:${serverA.port}" }
            registry.add("supplier.b.base-url") { "http://localhost:${serverB.port}" }
            // 무응답 테스트가 기본 5초를 기다리지 않게 줄이되, 전체 스위트 부하(컨테이너 기동 등) 속에서
            // 정상 응답까지 타임아웃으로 오판하지 않을 여유는 남긴다
            registry.add("supplier.response-timeout-ms") { "2000" }
        }

        @JvmStatic
        @AfterAll
        fun shutdown() {
            serverA.shutdown()
            serverB.shutdown()
        }
    }
}
