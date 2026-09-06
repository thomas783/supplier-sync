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
    fun `재고 요금 - net+tax 를 합산해 gross 총액으로 변환한다`() {
        enqueueJson(MockSupplierResponses.A_AVAILABILITY)

        val products = client.fetchStayProducts(query).block()!!

        val riverside = products.first { it.supplierPropertyCode == "A-10023" }
        // (120000+12000)+(150000+15000)+(120000+12000) = 429000
        assertEquals(429000, riverside.grossTotalAmount)
        assertEquals("KRW", riverside.currency)
        assertEquals(false, riverside.breakfastIncluded)
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
    fun `중복 날짜가 온 항목은 그 항목만 제외된다 - 총액 이중 합산을 막는 보수 방어`() {
        enqueueJson(
            """
            {
              "items": [
                { "hotelCode": "A-10023", "hotelName": "정상", "roomTypeCode": "DLX-TWN",
                  "roomTypeName": "Deluxe Twin", "maxOccupancy": 2, "breakfastIncluded": false, "currency": "KRW",
                  "dailyRates": [ { "date": "2026-09-01", "remainingRooms": 3, "nightlyRate": 100000, "taxAmount": 10000 } ] },
                { "hotelCode": "A-10044", "hotelName": "중복 날짜", "roomTypeCode": "STD-DBL",
                  "roomTypeName": "Standard Double", "maxOccupancy": 2, "breakfastIncluded": false, "currency": "KRW",
                  "dailyRates": [
                    { "date": "2026-09-01", "remainingRooms": 2, "nightlyRate": 88000, "taxAmount": 8800 },
                    { "date": "2026-09-01", "remainingRooms": 5, "nightlyRate": 88000, "taxAmount": 8800 }
                  ] }
              ]
            }
            """.trimIndent(),
        )

        val products = client.fetchStayProducts(query).block()!!

        assertEquals(listOf("A-10023"), products.map { it.supplierPropertyCode })
    }

    @Test
    fun `본문 없는 200 은 계약 위반 실패다 - 정상 빈 응답은 items 빈 배열로 온다`() {
        server.enqueue(MockResponse().setResponseCode(200)) // 본문 없음

        val ex = assertThrows(SupplierCallException::class.java) { client.fetchProperties() }
        assertTrue(ex.reason.contains("empty response"))
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
