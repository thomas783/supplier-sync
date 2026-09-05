package mocksupplier

import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.concurrent.ConcurrentHashMap

/**
 * Supplier A·B 를 흉내내는 Mock. 프로덕션 품질 기준의 대상이 아니며, 연동 견고성(타임아웃·부분 실패·실패 판정 통일)을
 * 사람이 실행해 확인하기 위한 세 가지 상황만 재현한다: 정상 / 장애 / 무응답.
 * (자동 테스트의 회귀 고정은 어댑터 테스트의 MockWebServer 가 별도로 담당한다 — docs/ARCHITECTURE.md)
 *
 * 모드 변경:
 *   curl -X POST 'http://localhost:9090/control/a/mode?value=no-response'
 *   curl -X POST 'http://localhost:9090/control/b/mode?value=error'
 *   curl -X POST 'http://localhost:9090/control/a/mode?value=normal'
 */
@RestController
class MockSupplierController {

    private val modes = ConcurrentHashMap<String, String>()

    @PostMapping("/control/{supplier}/mode")
    fun setMode(@PathVariable supplier: String, @RequestParam value: String): ResponseEntity<Map<String, String>> {
        // 오타(공급사든 모드든)가 조용히 normal 로 동작하면 장애 주입이 안 된 채 시연하게 된다 — 즉시 거부
        if (supplier !in VALID_SUPPLIERS) {
            return ResponseEntity.badRequest().body(mapOf("error" to "unknown supplier: $supplier (a|b)"))
        }
        if (value !in VALID_MODES) {
            return ResponseEntity.badRequest().body(mapOf("error" to "unknown mode: $value (normal|error|no-response)"))
        }
        modes[supplier] = value
        return ResponseEntity.ok(mapOf(supplier to value))
    }

    // ── ① 숙소 목록 (정적 콘텐츠) ─────────────────────────────
    @GetMapping("/a/v1/hotels", produces = ["application/json"])
    fun hotelsA(): ResponseEntity<String> = ResponseEntity.ok(A_HOTELS)

    @GetMapping("/b/api/properties", produces = ["application/json"])
    fun propertiesB(): ResponseEntity<String> = ResponseEntity.ok(B_PROPERTIES)

    // ── ② 재고·요금 조회 ──────────────────────────────────────
    @GetMapping("/a/v1/availability", produces = ["application/json"])
    fun availabilityA(@RequestParam hotelCodes: String): ResponseEntity<String> =
        when (modes["a"] ?: "normal") {
            "error" -> ResponseEntity.status(503)
                .body("""{"error":"SERVICE_UNAVAILABLE","message":"temporarily unavailable"}""")
            "no-response" -> { Thread.sleep(NO_RESPONSE_HOLD_MS); ResponseEntity.ok("{}") }
            else -> ResponseEntity.ok(A_AVAILABILITY)
        }

    @GetMapping("/b/api/search", produces = ["application/json"])
    fun searchB(@RequestParam propertyIds: String): ResponseEntity<String> =
        when (modes["b"] ?: "normal") {
            // B 는 장애 상황에서도 HTTP 200 + resultCode 로 실패를 알린다.
            "error" -> ResponseEntity.ok(
                """{"resultCode":"E503","resultMessage":"TEMPORARILY_UNAVAILABLE","data":null}""",
            )
            "no-response" -> { Thread.sleep(NO_RESPONSE_HOLD_MS); ResponseEntity.ok("{}") }
            else -> ResponseEntity.ok(B_SEARCH)
        }

    companion object {
        private val VALID_SUPPLIERS = setOf("a", "b")
        private val VALID_MODES = setOf("normal", "error", "no-response")

        // 호출 측 응답 타임아웃(5초)보다 충분히 길게 잡아 "무응답"을 재현한다
        private const val NO_RESPONSE_HOLD_MS = 600_000L

        private val A_HOTELS = """
            {
              "items": [
                { "hotelCode": "A-10023", "hotelName": "Riverside Hotel Seoul",
                  "roomTypes": [ { "roomTypeCode": "DLX-TWN", "roomTypeName": "Deluxe Twin", "maxOccupancy": 2 } ] },
                { "hotelCode": "A-10044", "hotelName": "Namsan Garden Stay",
                  "roomTypes": [ { "roomTypeCode": "STD-DBL", "roomTypeName": "Standard Double", "maxOccupancy": 2 } ] }
              ]
            }
        """.trimIndent()

        private val A_AVAILABILITY = """
            {
              "items": [
                { "hotelCode": "A-10023", "hotelName": "Riverside Hotel Seoul", "roomTypeCode": "DLX-TWN",
                  "roomTypeName": "Deluxe Twin", "maxOccupancy": 2, "breakfastIncluded": false, "currency": "KRW",
                  "dailyRates": [
                    { "date": "2026-09-01", "remainingRooms": 3, "nightlyRate": 120000, "taxAmount": 12000 },
                    { "date": "2026-09-02", "remainingRooms": 1, "nightlyRate": 150000, "taxAmount": 15000 },
                    { "date": "2026-09-03", "remainingRooms": 5, "nightlyRate": 120000, "taxAmount": 12000 }
                  ] },
                { "hotelCode": "A-10044", "hotelName": "Namsan Garden Stay", "roomTypeCode": "STD-DBL",
                  "roomTypeName": "Standard Double", "maxOccupancy": 2, "breakfastIncluded": false, "currency": "KRW",
                  "dailyRates": [
                    { "date": "2026-09-01", "remainingRooms": 2, "nightlyRate": 88000, "taxAmount": 8800 },
                    { "date": "2026-09-02", "remainingRooms": 0, "nightlyRate": 99000, "taxAmount": 9900 },
                    { "date": "2026-09-03", "remainingRooms": 4, "nightlyRate": 88000, "taxAmount": 8800 }
                  ] }
              ]
            }
        """.trimIndent()

        private val B_PROPERTIES = """
            {
              "resultCode": "0000", "resultMessage": "SUCCESS",
              "data": { "items": [
                { "propertyId": "B77120", "propertyName": "Riverside Hotel Seoul",
                  "rooms": [ { "roomId": "R-401", "roomName": "Deluxe Twin Room", "maxOccupancy": 2 } ] }
              ] }
            }
        """.trimIndent()

        private val B_SEARCH = """
            {
              "resultCode": "0000", "resultMessage": "SUCCESS",
              "data": { "items": [
                { "propertyId": "B77120", "propertyName": "Riverside Hotel Seoul", "roomId": "R-401",
                  "roomName": "Deluxe Twin Room", "maxOccupancy": 2, "breakfastIncluded": true, "currency": "KRW",
                  "totalPrice": 452000, "taxIncluded": true,
                  "inventory": [
                    { "date": "2026-09-01", "remainingRooms": 3 },
                    { "date": "2026-09-02", "remainingRooms": 1 },
                    { "date": "2026-09-03", "remainingRooms": 5 }
                  ] }
              ] }
            }
        """.trimIndent()
    }
}
