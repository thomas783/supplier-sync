package com.staysync.search

import com.staysync.config.SupplierProperties
import com.staysync.domain.model.Availability
import com.staysync.domain.model.Property
import com.staysync.domain.model.RoomType
import com.staysync.domain.model.Supplier
import com.staysync.domain.repository.PropertyRepository
import com.staysync.domain.repository.RoomTypeRepository
import com.staysync.supplier.StayProductQuery
import com.staysync.supplier.SupplierCallException
import com.staysync.supplier.SupplierClient
import com.staysync.supplier.SupplierProperty
import com.staysync.supplier.SupplierStayProduct
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import reactor.core.publisher.Mono
import java.time.LocalDate
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 검색 오케스트레이션의 단위 테스트 — 청킹·정규화 조립·부분 실패 격리·오류 병합처럼 HTTP 없이 검증
 * 가능한 로직만 다룬다. 실제 왕복(어댑터·웹 계층 포함)은 StaySearchIntegrationTest 가 담당한다.
 */
class StaySearchServiceTest {

    private val criteria = StaySearchCriteria(
        checkIn = LocalDate.of(2026, 9, 1),
        checkOut = LocalDate.of(2026, 9, 3),
        adults = 2,
        children = 0,
    )

    @Test
    fun `청킹 - 50개 초과 숙소 코드는 여러 호출로 나뉘고 전량 커버된다`() {
        val codes = (1..120).map { "A-%03d".format(it) }
        val clientA = FakeSupplierClient(Supplier.A) { Mono.just(emptyList()) }

        val result = service(listOf(clientA), mapOf(Supplier.A to plan(Supplier.A, codes))).search(criteria)

        assertEquals(listOf(50, 50, 20), clientA.queries.map { it.propertyCodes.size }.sortedDescending())
        assertEquals(codes.toSet(), clientA.queries.flatMap { it.propertyCodes }.toSet())
        assertTrue(result.stays.isEmpty() && result.errors.isEmpty())
    }

    @Test
    fun `정규화 - 매핑 치환, 가용성 판정, 요금 계산이 표준 모델로 조립된다`() {
        val clientB = FakeSupplierClient(Supplier.B) { Mono.just(listOf(B_PRODUCT)) }

        val result = service(listOf(clientB), mapOf(Supplier.B to plan(Supplier.B, listOf("B77120"), B_LOOKUP)))
            .search(criteria)

        val stay = result.stays.single()
        assertEquals(3L, stay.property.id)
        assertEquals("Riverside Hotel Seoul", stay.property.name)
        assertEquals(3L, stay.roomType.id)
        assertEquals(Availability.Available(1), stay.availability) // 병목 최소값: [3, 1] → 1
        assertEquals(452_000L, stay.price.totalAmount)
        assertEquals(226_000L, stay.price.averageNightlyAmount) // 2박 내림
        assertEquals(Supplier.B, stay.supplier)
    }

    @Test
    fun `미매핑 상품 - 그 상품만 건너뛰고 매핑된 상품은 조립된다`() {
        val unmapped = B_PRODUCT.copy(supplierPropertyCode = "B99999")
        val clientB = FakeSupplierClient(Supplier.B) { Mono.just(listOf(B_PRODUCT, unmapped)) }

        val result = service(listOf(clientB), mapOf(Supplier.B to plan(Supplier.B, listOf("B77120"), B_LOOKUP)))
            .search(criteria)

        assertEquals(3L, result.stays.single().property.id)
        assertTrue(result.errors.isEmpty())
    }

    @Test
    fun `날짜 누락 - 미확정으로 조립하되 결과에서 빼지 않는다, 노출 여부는 웹 계층의 몫`() {
        val partial = B_PRODUCT.copy(remainingByDate = mapOf(LocalDate.of(2026, 9, 1) to 3)) // 09-02 누락
        val clientB = FakeSupplierClient(Supplier.B) { Mono.just(listOf(partial)) }

        val result = service(listOf(clientB), mapOf(Supplier.B to plan(Supplier.B, listOf("B77120"), B_LOOKUP)))
            .search(criteria)

        assertEquals(Availability.Undetermined, result.stays.single().availability)
    }

    @Test
    fun `정규화 실패 - 깨진 금액은 청크 실패로 흡수되되 내부 메시지는 노출되지 않는다`() {
        // 음수 금액에 가드를 더하지 않는 대신, 도메인 불변식(Price)이 던진 예외가 조용히 사라지지 않고
        // 청크 단위 부분 실패로 드러나는 것을 고정한다. 단, 공개 reason 은 불투명한 분류 문자열이어야
        // 한다 — 불변식 위반 메시지 같은 내부 구현 상세는 로그에만 남는다 (docs/API.md)
        val broken = B_PRODUCT.copy(grossTotalAmount = -1)
        val clientB = FakeSupplierClient(Supplier.B) { Mono.just(listOf(broken)) }

        val result = service(listOf(clientB), mapOf(Supplier.B to plan(Supplier.B, listOf("B77120"), B_LOOKUP)))
            .search(criteria)

        assertTrue(result.stays.isEmpty())
        assertEquals(SupplierError(Supplier.B, "internal error"), result.errors.single())
    }

    @Test
    fun `부분 실패 - 한 공급사의 실패가 다른 공급사 결과를 막지 않는다`() {
        val clientA = FakeSupplierClient(Supplier.A) {
            Mono.error(SupplierCallException(Supplier.A, "/a/v1/availability HTTP 503", retryable = true))
        }
        val clientB = FakeSupplierClient(Supplier.B) { Mono.just(listOf(B_PRODUCT)) }

        val result = service(
            listOf(clientA, clientB),
            mapOf(
                Supplier.A to plan(Supplier.A, listOf("A-10023")),
                Supplier.B to plan(Supplier.B, listOf("B77120"), B_LOOKUP),
            ),
        ).search(criteria)

        assertEquals(Supplier.B, result.stays.single().supplier)
        assertEquals(SupplierError(Supplier.A, "/a/v1/availability HTTP 503"), result.errors.single())
    }

    @Test
    fun `반복 실패 - 같은 공급사·사유의 청크 실패는 하나의 오류로 합쳐진다`() {
        val codes = (1..120).map { "A-%03d".format(it) } // 3청크 → 실패 3건 발생
        val clientA = FakeSupplierClient(Supplier.A) {
            Mono.error(SupplierCallException(Supplier.A, "/a/v1/availability HTTP 503", retryable = true))
        }

        val result = service(listOf(clientA), mapOf(Supplier.A to plan(Supplier.A, codes))).search(criteria)

        assertEquals(listOf(SupplierError(Supplier.A, "/a/v1/availability HTTP 503")), result.errors)
    }

    @Test
    fun `매핑 없음 - 공급사를 호출하지 않고 빈 결과를 반환한다`() {
        val clientA = FakeSupplierClient(Supplier.A) { Mono.just(emptyList()) }

        val result = service(listOf(clientA), emptyMap()).search(criteria)

        assertTrue(result.stays.isEmpty() && result.errors.isEmpty())
        assertTrue(clientA.queries.isEmpty())
    }

    @Test
    fun `Error 계열 실패 - 부분 실패로 흡수하지 않고 밖으로 던진다`() {
        val clientA = FakeSupplierClient(Supplier.A) { Mono.error(NotImplementedError("치명적 상태")) }

        val thrown = assertThrows(Throwable::class.java) {
            service(listOf(clientA), mapOf(Supplier.A to plan(Supplier.A, listOf("A-10023")))).search(criteria)
        }

        assertTrue(generateSequence(thrown) { it.cause }.any { it is NotImplementedError })
    }

    private fun service(clients: List<SupplierClient>, plans: Map<Supplier, SupplierQueryPlan>) =
        StaySearchService(clients, FakeMappingQueryService(plans), SUPPLIER_PROPERTIES)

    private fun plan(
        supplier: Supplier,
        codes: List<String>,
        lookup: MappingLookup = MappingLookup(supplier, emptyMap(), emptyMap()),
    ) = SupplierQueryPlan(supplier, codes, lookup)

    /** 조회 계획을 고정값으로 주는 페이크 — 리포지토리는 쓰지 않지만 부모 생성자가 요구해 자리만 채운다. */
    private class FakeMappingQueryService(
        private val plans: Map<Supplier, SupplierQueryPlan>,
    ) : MappingQueryService(
        Mockito.mock(PropertyRepository::class.java),
        Mockito.mock(RoomTypeRepository::class.java),
    ) {
        override fun loadPlan(supplier: Supplier): SupplierQueryPlan? = plans[supplier]
    }

    private class FakeSupplierClient(
        override val supplier: Supplier,
        private val outcome: () -> Mono<List<SupplierStayProduct>>,
    ) : SupplierClient {
        // 청크들이 병렬로 구독되므로 기록은 동시성 안전 컬렉션에 담는다
        val queries = CopyOnWriteArrayList<StayProductQuery>()

        override fun fetchProperties(): List<SupplierProperty> = error("검색 테스트에서는 호출되지 않는다")

        override fun fetchStayProducts(query: StayProductQuery): Mono<List<SupplierStayProduct>> {
            queries += query
            return outcome()
        }
    }

    companion object {
        private val SUPPLIER_PROPERTIES = SupplierProperties(
            connectTimeoutMs = 1000,
            responseTimeoutMs = 5000,
            maxConcurrentCalls = 16,
            a = SupplierProperties.Endpoint(baseUrl = "http://localhost", apiKey = "unused"),
            b = SupplierProperties.Endpoint(baseUrl = "http://localhost", apiKey = "unused"),
        )

        private val B_LOOKUP = MappingLookup(
            supplier = Supplier.B,
            propertyByCode = mapOf("B77120" to Property(id = 3, name = "Riverside Hotel Seoul")),
            roomTypeByKey = mapOf((3L to "R-401") to RoomType(id = 3, name = "Deluxe Twin Room", maxOccupancy = 2)),
        )

        private val B_PRODUCT = SupplierStayProduct(
            supplierPropertyCode = "B77120",
            propertyName = "Riverside Hotel Seoul",
            supplierRoomTypeCode = "R-401",
            roomTypeName = "Deluxe Twin Room",
            maxOccupancy = 2,
            breakfastIncluded = true,
            currency = "KRW",
            grossTotalAmount = 452_000,
            remainingByDate = mapOf(LocalDate.of(2026, 9, 1) to 3, LocalDate.of(2026, 9, 2) to 1),
        )
    }
}
