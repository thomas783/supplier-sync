package com.staysync.supplier.a

import com.staysync.observability.SupplierMetrics
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import com.staysync.config.SupplierProperties
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
    private val registry = SimpleMeterRegistry()

    private fun quarantinedCount(reason: String): Double =
        registry.find(SupplierMetrics.QUARANTINED_COUNTER).tags("supplier", "A", "reason", reason).counter()?.count() ?: 0.0

    private val query = StayProductQuery(
        propertyCodes = listOf("A-10023", "A-10044"),
        checkIn = LocalDate.of(2026, 9, 1),
        checkOut = LocalDate.of(2026, 9, 4),
        adults = 2,
        children = 0,
    )

    private val properties = SupplierProperties(
        connectTimeoutMs = 1000,
        searchResponseTimeoutMs = 500,
        syncResponseTimeoutMs = 3000, // 기본(500ms)과 달리 두어 요청 단위 오버라이드 적용을 검증한다
        searchDeadlineMs = 10_000,
        maxConnections = 32,
        pendingAcquireTimeoutMs = 2000,
        a = SupplierProperties.Endpoint(baseUrl = "unused", apiKey = "unused"),
        b = SupplierProperties.Endpoint(baseUrl = "unused", apiKey = "unused"),
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
        client = SupplierAClient(webClient, properties, SupplierMetrics(registry))
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
    fun `값 결함 항목은 그 항목만 제외된다 - 변환 관문이 음수 총액을 잡는다`() {
        enqueueJson(
            """
            {
              "items": [
                { "hotelCode": "A-10023", "hotelName": "정상", "roomTypeCode": "DLX-TWN",
                  "roomTypeName": "Deluxe Twin", "maxOccupancy": 2, "breakfastIncluded": false, "currency": "KRW",
                  "dailyRates": [ { "date": "2026-09-01", "remainingRooms": 3, "nightlyRate": 100000, "taxAmount": 10000 } ] },
                { "hotelCode": "A-10044", "hotelName": "음수 요금", "roomTypeCode": "STD-DBL",
                  "roomTypeName": "Standard Double", "maxOccupancy": 2, "breakfastIncluded": false, "currency": "KRW",
                  "dailyRates": [ { "date": "2026-09-01", "remainingRooms": 2, "nightlyRate": -88000, "taxAmount": 8800 } ] }
              ]
            }
            """.trimIndent(),
        )

        val products = client.fetchStayProducts(query).block()!!

        assertEquals(listOf("A-10023"), products.map { it.supplierPropertyCode })
        // 검색 경로 결함이 대시보드에 보이도록 사유별 카운터로 집계된다 (docs/MONITORING.md)
        assertEquals(1.0, quarantinedCount("INVALID_PRICE"))
    }

    @Test
    fun `요청 기간 밖 날짜는 총액과 잔여에서 제외된다 - 총액이 요청 박수와 무관하게 부풀지 않게`() {
        // query 는 09-01~09-04(요청 숙박일 09-01·02·03). 공급사가 09-04 를 덧붙여 반환해도 무시한다
        enqueueJson(
            """
            {
              "items": [
                { "hotelCode": "A-10023", "hotelName": "정상", "roomTypeCode": "DLX-TWN",
                  "roomTypeName": "Deluxe Twin", "maxOccupancy": 2, "breakfastIncluded": false, "currency": "KRW",
                  "dailyRates": [
                    { "date": "2026-09-01", "remainingRooms": 3, "nightlyRate": 100000, "taxAmount": 10000 },
                    { "date": "2026-09-02", "remainingRooms": 3, "nightlyRate": 100000, "taxAmount": 10000 },
                    { "date": "2026-09-03", "remainingRooms": 3, "nightlyRate": 100000, "taxAmount": 10000 },
                    { "date": "2026-09-04", "remainingRooms": 3, "nightlyRate": 999999, "taxAmount": 999999 }
                  ] }
              ]
            }
            """.trimIndent(),
        )

        val product = client.fetchStayProducts(query).block()!!.single()

        assertEquals(330000, product.grossTotalAmount) // 09-04 제외한 3일치 (110000 × 3)
        assertEquals(setOf(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 2), LocalDate.of(2026, 9, 3)), product.remainingByDate.keys)
    }

    @Test
    fun `일자별 음수 요금은 합계가 양수여도 제외된다 - 관문은 합계만 보므로 어댑터가 일자별로 잡는다`() {
        enqueueJson(
            """
            {
              "items": [
                { "hotelCode": "A-10044", "hotelName": "상쇄된 음수", "roomTypeCode": "STD-DBL",
                  "roomTypeName": "Standard Double", "maxOccupancy": 2, "breakfastIncluded": false, "currency": "KRW",
                  "dailyRates": [
                    { "date": "2026-09-01", "remainingRooms": 2, "nightlyRate": 200000, "taxAmount": 0 },
                    { "date": "2026-09-02", "remainingRooms": 2, "nightlyRate": -50000, "taxAmount": 0 },
                    { "date": "2026-09-03", "remainingRooms": 2, "nightlyRate": 100000, "taxAmount": 0 }
                  ] }
              ]
            }
            """.trimIndent(),
        )

        val products = client.fetchStayProducts(query).block()!!

        assertTrue(products.isEmpty()) // 합계 250000 은 양수지만 09-02 가 음수라 제외
        assertEquals(1.0, quarantinedCount("INVALID_PRICE"))
    }

    @Test
    fun `숙소 목록 - 어댑터는 결함을 거르지 않고 원시 그대로 통과시킨다 (필터는 저장 경계의 몫)`() {
        // sync 경로의 결함 판정·집계는 PropertyMappingService(ConversionGate.defectOf)의 몫이다
        // (docs/QUARANTINE.md). 어댑터는 형식만 통일하고 계약 밖 레코드도 그대로 넘긴다.
        enqueueJson(
            """
            {
              "items": [
                { "hotelCode": "A-10023", "hotelName": "Riverside Hotel Seoul",
                  "roomTypes": [ { "roomTypeCode": "DLX-TWN", "roomTypeName": "Deluxe Twin", "maxOccupancy": 2 } ] },
                { "hotelCode": "A-10044", "hotelName": " ",
                  "roomTypes": [ { "roomTypeCode": "STD-DBL", "roomTypeName": "Standard Double", "maxOccupancy": 2 } ] }
              ]
            }
            """.trimIndent(),
        )

        val properties = client.fetchProperties()

        assertEquals(listOf("A-10023", "A-10044"), properties.map { it.supplierPropertyCode })
    }

    @Test
    fun `역직렬화가 깨지는 본문은 호출 전체가 재시도 불가 실패다 - 항목 단위 제외가 아니다`() {
        // 엄격 DTO 의 현재 동작 — 항목 하나의 위반도 전체 파싱을 죽인다. 항목 수준 결함을 격리로
        // 살리는 관용 파싱 전환은 격리 기록과 함께 구현 예정 (docs/QUARANTINE.md 역직렬화 두 층위)
        enqueueJson("""{"items": [ { "hotelCode": 123, "dailyRates": "broken" } ]}""")

        val ex = assertThrows(SupplierCallException::class.java) {
            client.fetchStayProducts(query).block()
        }
        assertEquals(false, ex.retryable)
    }

    @Test
    fun `본문 없는 200 은 계약 위반 실패다 - 정상 빈 응답은 items 빈 배열로 온다`() {
        server.enqueue(MockResponse().setResponseCode(200)) // 본문 없음

        val ex = assertThrows(SupplierCallException::class.java) { client.fetchProperties() }
        assertTrue(ex.reason.contains("empty response"))
    }

    @Test
    fun `동기화 타임아웃 - 검색용 기본을 넘는 지연도 요청 단위 오버라이드로 기다린다`() {
        // 클라이언트 기본(500ms)보다 길고 동기화 오버라이드(3초)보다 짧은 지연 — 오버라이드가 조용히
        // 무시되면(캐스팅 회귀 등) 기본 타임아웃에 잘려 이 테스트가 실패한다
        server.enqueue(
            MockResponse()
                .setHeadersDelay(1200, java.util.concurrent.TimeUnit.MILLISECONDS)
                .setResponseCode(200)
                .setBody(MockSupplierResponses.A_HOTELS)
                .addHeader("Content-Type", "application/json"),
        )

        val properties = client.fetchProperties()

        assertEquals(2, properties.size)
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
