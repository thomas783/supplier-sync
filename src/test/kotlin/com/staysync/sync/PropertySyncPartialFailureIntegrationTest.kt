package com.staysync.sync

import com.staysync.TestcontainersConfiguration
import com.staysync.domain.model.Supplier
import com.staysync.domain.repository.PropertyRepository
import com.staysync.domain.repository.RoomTypeRepository
import com.staysync.support.MockSupplierResponses
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.hamcrest.Matchers.contains
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * 수동 재동기화 엔드포인트의 청크 부분 실패 통합 검증 — HTTP → 컨트롤러 → [PropertySyncService] → 저장 →
 * 실제 MySQL(Testcontainers)까지 전 구간을 실제 HTTP 왕복(공급사별 MockWebServer)으로 확인한다.
 * persistMappings 를 직접 부르는 컴포넌트 테스트([PropertyMappingChunkFailureTest])와 달리, 여기서는
 * ok=false·failed 가 서비스에서 산출되어 응답 JSON 으로까지 실리는지를 계약 수준에서 고정한다.
 *
 * A 목록은 이름이 컬럼 길이를 넘는 건을 앞 청크에 둬(chunk-size=2) 첫 청크가 저장 실패로 롤백되게 하고,
 * 둘째 청크는 best-effort 로 커밋되는지, B 는 정상인지 확인한다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration::class)
@AutoConfigureMockMvc
class PropertySyncPartialFailureIntegrationTest {

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var propertyRepository: PropertyRepository
    @Autowired private lateinit var roomTypeRepository: RoomTypeRepository

    // 커밋되는 통합 테스트라 공유 컨테이너에 흔적을 남기지 않는다 — 기동 시 동기화가 채운 것도 여기서 비운다
    @BeforeEach
    @AfterEach
    fun clean() {
        roomTypeRepository.deleteAll()
        propertyRepository.deleteAll()
    }

    @Test
    fun `부분 실패 - A 의 한 청크가 실패해도 나머지는 커밋되고 응답에 ok=false·failed 가 실린다`() {
        mockMvc.perform(post("/internal/properties/sync"))
            .andExpect(status().isOk)
            // A: 첫 청크[A-BAD, A-2] 롤백, 둘째 청크[A-OK1, A-OK2] 커밋 → 부분 실패
            .andExpect(jsonPath("$[?(@.supplier == 'A')].ok", contains(false)))
            .andExpect(jsonPath("$[?(@.supplier == 'A')].properties", contains(2)))
            // 실패 청크의 레코드 수 = 상품 2 + 각 소속 객실 1 = 4
            .andExpect(jsonPath("$[?(@.supplier == 'A')].failed", contains(4)))
            // B: 정상 — 부분 실패는 공급사 단위로 격리된다
            .andExpect(jsonPath("$[?(@.supplier == 'B')].ok", contains(true)))
            .andExpect(jsonPath("$[?(@.supplier == 'B')].failed", contains(0)))

        // 부분 커밋: 실패 청크(A-BAD·A-2)는 남지 않고, 성공 청크(A-OK1·A-OK2)는 커밋되어 살아 있다
        assertNull(propertyRepository.findBySupplierAndSupplierPropertyCode(Supplier.A, "A-BAD"))
        assertEquals(2, propertyRepository.findAllBySupplier(Supplier.A).size)
    }

    companion object {
        private val serverA = MockWebServer()
        private val serverB = MockWebServer()

        // property_name 컬럼(기본 VARCHAR(255))을 넘겨 저장 시 DB 제약 위반을 유발한다 (앱 검증은 통과)
        private val TOO_LONG_NAME = "X".repeat(512)

        // A-BAD 는 이름이 컬럼 길이를 넘겨 INSERT 가 실패한다 — chunk-size=2 로 첫 청크[A-BAD, A-2]가 롤백된다
        private val A_HOTELS_WITH_BAD = """
            {
              "items": [
                { "hotelCode": "A-BAD", "hotelName": "$TOO_LONG_NAME",
                  "roomTypes": [ { "roomTypeCode": "R1", "roomTypeName": "룸", "maxOccupancy": 2 } ] },
                { "hotelCode": "A-2", "hotelName": "정상이지만 실패 청크에 묶인 숙소",
                  "roomTypes": [ { "roomTypeCode": "R1", "roomTypeName": "룸", "maxOccupancy": 2 } ] },
                { "hotelCode": "A-OK1", "hotelName": "정상 A1",
                  "roomTypes": [ { "roomTypeCode": "R1", "roomTypeName": "룸", "maxOccupancy": 2 } ] },
                { "hotelCode": "A-OK2", "hotelName": "정상 A2",
                  "roomTypes": [ { "roomTypeCode": "R1", "roomTypeName": "룸", "maxOccupancy": 2 } ] }
              ]
            }
        """.trimIndent()

        private fun json(body: String) =
            MockResponse().setResponseCode(200).setBody(body).addHeader("Content-Type", "application/json")

        private fun dispatcher(path: String, body: String) = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if ((request.path ?: "").startsWith(path)) json(body) else MockResponse().setResponseCode(404)
        }

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            serverA.dispatcher = dispatcher("/a/v1/hotels", A_HOTELS_WITH_BAD)
            serverB.dispatcher = dispatcher("/b/api/properties", MockSupplierResponses.B_PROPERTIES)
            serverA.start()
            serverB.start()
            registry.add("supplier.a.base-url") { "http://localhost:${serverA.port}" }
            registry.add("supplier.b.base-url") { "http://localhost:${serverB.port}" }
            // 작은 데이터로 청크 경계를 만든다 — [A-DUP, A-DUP] | [A-OK1, A-OK2]
            registry.add("sync.chunk-size") { "2" }
        }

        @JvmStatic
        @AfterAll
        fun shutdown() {
            serverA.shutdown()
            serverB.shutdown()
        }
    }
}
