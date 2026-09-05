package com.staysync.supplier.b

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

class SupplierBClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: SupplierBClient

    private val query = StayProductQuery(
        propertyCodes = listOf("B77120"),
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
        client = SupplierBClient(webClient)
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
        enqueueJson(MockSupplierResponses.B_PROPERTIES)

        val properties = client.fetchProperties()

        assertEquals(1, properties.size)
        assertEquals("B77120", properties[0].supplierPropertyCode)
        assertEquals("R-401", properties[0].roomTypes[0].supplierRoomTypeCode)
    }

    @Test
    fun `숙소 목록 - resultCode 가 0000 이 아니면 실패로 다룬다`() {
        enqueueJson(MockSupplierResponses.B_ERROR) // HTTP 200 + E503

        val ex = assertThrows(SupplierCallException::class.java) { client.fetchProperties() }
        assertTrue(ex.reason.contains("E503"))
    }

    @Test
    fun `재고 요금 - totalPrice 를 gross 총액으로 담고 일자별 실측은 비운다`() {
        enqueueJson(MockSupplierResponses.B_SEARCH)

        val product = client.fetchStayProducts(query).block()!!.single()

        assertEquals(452000, product.grossTotalAmount)
        assertEquals("KRW", product.currency)
        assertEquals(true, product.breakfastIncluded)
        // B 는 일자별 실측을 주지 않는다 — 평균을 복제해 채우지 않고 빈 맵으로 둔다
        assertEquals(emptyMap<LocalDate, Long>(), product.nightlyAmountsByDate)
        assertEquals(
            mapOf(
                LocalDate.of(2026, 9, 1) to 3,
                LocalDate.of(2026, 9, 2) to 1,
                LocalDate.of(2026, 9, 3) to 5,
            ),
            product.remainingByDate,
        )
    }

    @Test
    fun `실패 판정 통일 - HTTP 200 이어도 resultCode 로 실패를 판정하고 E503 은 재시도 가능이다`() {
        enqueueJson(MockSupplierResponses.B_ERROR) // HTTP 200 + resultCode E503

        val ex = assertThrows(SupplierCallException::class.java) {
            client.fetchStayProducts(query).block()
        }
        assertTrue(ex.reason.contains("E503"))
        assertTrue(ex.retryable)
    }

    @Test
    fun `E400 은 재시도 불가로 분류된다`() {
        enqueueJson("""{"resultCode":"E400","resultMessage":"BAD_REQUEST","data":null}""")

        val ex = assertThrows(SupplierCallException::class.java) {
            client.fetchStayProducts(query).block()
        }
        assertTrue(ex.reason.contains("E400"))
        assertEquals(false, ex.retryable)
    }

    @Test
    fun `성공 코드인데 data 가 없으면 계약 위반 실패다 - 정상 빈 결과는 data 안의 빈 items 로 온다`() {
        enqueueJson("""{"resultCode":"0000","resultMessage":"SUCCESS","data":null}""")

        val ex = assertThrows(SupplierCallException::class.java) {
            client.fetchStayProducts(query).block()
        }
        assertTrue(ex.reason.contains("success without data"))
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
