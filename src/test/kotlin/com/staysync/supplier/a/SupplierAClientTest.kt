package com.staysync.supplier.a

import com.staysync.supplier.StayProductQuery
import com.staysync.supplier.SupplierCallException
import com.staysync.support.MockSupplierResponses
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.web.reactive.function.client.WebClient
import reactor.netty.http.client.HttpClient
import java.time.Duration
import java.time.LocalDate

class SupplierAClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: SupplierAClient

    private val query = StayProductQuery(
        propertyCodes = listOf("A-10023", "A-10044"),
        checkIn = LocalDate.of(2026, 9, 1),
        checkOut = LocalDate.of(2026, 9, 4),
        adults = 2,
        children = 0,
    )

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        val httpClient = HttpClient.create().responseTimeout(Duration.ofMillis(500))
        val webClient = WebClient.builder()
            .baseUrl(server.url("/").toString())
            .defaultHeader("X-Api-Key", "test-key")
            .clientConnector(ReactorClientHttpConnector(httpClient))
            .build()
        client = SupplierAClient(webClient)
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    private fun enqueueJson(body: String, code: Int = 200) {
        server.enqueue(
            MockResponse().setResponseCode(code).setBody(body).addHeader("Content-Type", "application/json"),
        )
    }

    @Test
    fun `숙소 목록을 중간 표준 타입으로 변환한다`() {
        enqueueJson(MockSupplierResponses.A_HOTELS)

        val properties = client.fetchProperties()

        assertEquals(2, properties.size)
        val riverside = properties.first { it.supplierPropertyCode == "A-10023" }
        assertEquals("Riverside Hotel Seoul", riverside.propertyName)
        assertEquals(1, riverside.roomTypes.size)
        assertEquals("DLX-TWN", riverside.roomTypes[0].supplierRoomTypeCode)
        assertEquals(2, riverside.roomTypes[0].maxOccupancy)
    }

    @Test
    fun `재고 요금 - net+tax 를 합산해 gross 총액으로 변환하고 일자별 실측을 보존한다`() {
        enqueueJson(MockSupplierResponses.A_AVAILABILITY)

        val products = client.fetchStayProducts(query).block()!!

        val riverside = products.first { it.supplierPropertyCode == "A-10023" }
        // (120000+12000)+(150000+15000)+(120000+12000) = 429000
        assertEquals(429000, riverside.grossTotalAmount)
        assertEquals("KRW", riverside.currency)
        assertEquals(false, riverside.breakfastIncluded)
        // 일자별 실측은 gross(단가+세금)로 보존된다
        assertEquals(
            mapOf(
                LocalDate.of(2026, 9, 1) to 132000L,
                LocalDate.of(2026, 9, 2) to 165000L,
                LocalDate.of(2026, 9, 3) to 132000L,
            ),
            riverside.nightlyAmountsByDate,
        )
        assertEquals(
            mapOf(
                LocalDate.of(2026, 9, 1) to 3,
                LocalDate.of(2026, 9, 2) to 1,
                LocalDate.of(2026, 9, 3) to 5,
            ),
            riverside.remainingByDate,
        )
    }

    @Test
    fun `요청에 코드 목록과 날짜, 인증 헤더가 담긴다`() {
        enqueueJson(MockSupplierResponses.A_AVAILABILITY)

        client.fetchStayProducts(query).block()

        val request = server.takeRequest()
        assertTrue(request.path!!.contains("/a/v1/availability"))
        assertTrue(request.path!!.contains("A-10023"))
        assertTrue(request.path!!.contains("checkIn=2026-09-01"))
        assertTrue(request.path!!.contains("checkOut=2026-09-04"))
        assertEquals("test-key", request.getHeader("X-Api-Key"))
    }

    @Test
    fun `HTTP 503 은 재시도 가능한 SupplierCallException 으로 변환된다`() {
        enqueueJson(MockSupplierResponses.A_ERROR, code = 503)

        val ex = assertThrows(SupplierCallException::class.java) {
            client.fetchStayProducts(query).block()
        }
        assertTrue(ex.reason.contains("503"))
        assertTrue(ex.retryable)
    }

    @Test
    fun `HTTP 400 은 재시도 불가로 분류된다`() {
        enqueueJson("""{"error":"BAD_REQUEST"}""", code = 400)

        val ex = assertThrows(SupplierCallException::class.java) {
            client.fetchStayProducts(query).block()
        }
        assertTrue(ex.reason.contains("400"))
        assertEquals(false, ex.retryable)
    }

    @Test
    fun `무응답은 타임아웃으로 끊고 재시도 가능한 SupplierCallException 으로 변환된다`() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))

        val ex = assertThrows(SupplierCallException::class.java) {
            client.fetchStayProducts(query).block()
        }
        assertTrue(ex.reason.contains("timeout"))
        assertTrue(ex.retryable)
    }
}
