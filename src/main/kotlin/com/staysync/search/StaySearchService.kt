package com.staysync.search

import com.staysync.config.SupplierProperties
import com.staysync.domain.model.AvailabilityPolicy
import com.staysync.domain.model.Price
import com.staysync.domain.model.StayProduct
import com.staysync.domain.model.Supplier
import com.staysync.supplier.StayProductQuery
import com.staysync.supplier.SupplierCallException
import com.staysync.supplier.SupplierClient
import com.staysync.supplier.SupplierStayProduct
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.time.LocalDate

/**
 * 통합 검색 유스케이스 (흐름의 규칙은 docs/ARCHITECTURE.md).
 *
 * 매핑에서 공급사별 보유 숙소 코드를 미리 로드(readOnly, 원격 호출 전에 종료) → 50개씩 청킹 → 여러
 * 공급사·청크를 논블로킹으로 병렬 조회 → 응답을 표준 모델로 정규화 → 병합. 청크 하나의 실패는
 * [ChunkOutcome.Failure] 로 흡수돼 스트림을 죽이지 않는다 — 한 공급사가 실패해도 나머지 결과로 응답하고
 * 실패 사실은 errors 로 드러난다.
 */
@Service
class StaySearchService(
    private val clients: List<SupplierClient>,
    private val mappingQueryService: MappingQueryService,
    supplierProperties: SupplierProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val maxConcurrentCalls = supplierProperties.maxConcurrentCalls

    fun search(criteria: StaySearchCriteria): StaySearchResult {
        val stayDates = criteria.stayDates()

        // DB 읽기 국면 — 원격 호출 전에 매핑 읽기(readOnly 트랜잭션)를 전부 끝내고,
        // 아직 실행되지 않은 cold Mono 작업 목록만 손에 남긴다
        val tasks = clients.flatMap { client ->
            val plan = mappingQueryService.loadPlan(client.supplier) ?: return@flatMap emptyList()
            buildTasks(client, plan, criteria, stayDates)
        }
        // 매핑이 비어 있으면 오류가 아니라 "결과 없음"이다 (docs/API.md)
        if (tasks.isEmpty()) return StaySearchResult(emptyList(), emptyList())

        val outcomes = Flux.fromIterable(tasks)
            // 구독 = 실행. 여기서 처음 HTTP 가 나간다 — 동시 구독은 상한(yml)까지, 결과는 완료 순서로
            // 도착한다(순서 비보장). 실패는 이미 Failure 값이라 형제 청크를 취소시키는 에러가 흐르지 않는다
            .flatMap({ it }, maxConcurrentCalls)
            // 모든 청크가 결론(성공/실패 값)에 이를 때까지 모으는 의도된 장벽
            .collectList()
            // 논블로킹 팬아웃이 값으로 수렴하는 유일한 지점 — MVC 컨트롤러가 동기 호출하는 경계다
            .block()
            // collectList 는 항상 리스트를 방출하지만 block() 의 반환 타입은 nullable — !! 대신 빈 결과로
            .orEmpty()

        // 여기부터는 리액티브가 끝난 일반 컬렉션 조작 — sealed 타입으로 성공/실패를 가른다
        val stays = outcomes.filterIsInstance<ChunkOutcome.Success>().flatMap { it.products }
        val errors = outcomes.filterIsInstance<ChunkOutcome.Failure>()
            .map { SupplierError(it.supplier, it.reason) }
            .distinct() // 같은 공급사의 여러 청크가 같은 사유로 실패하면 하나로 합친다
        return StaySearchResult(stays = stays, errors = errors)
    }

    private fun buildTasks(
        client: SupplierClient,
        plan: SupplierQueryPlan,
        criteria: StaySearchCriteria,
        stayDates: List<LocalDate>,
    ): List<Mono<ChunkOutcome>> =
        // 재고·요금 API 는 한 호출당 최대 50개 코드를 받는다 — 청킹은 계약의 직접 귀결 (docs/INTEGRATION.md)
        plan.propertyCodes.chunked(MAX_CODES_PER_CALL).map { chunk ->
            // 청크 사이에 달라지는 것은 코드 묶음뿐 — 호출 1건 = 쿼리 1개 = 코드 50개 이하
            val query = StayProductQuery(chunk, criteria.checkIn, criteria.checkOut, criteria.adults, criteria.children)
            // cold Mono — 여기서는 HTTP 가 나가지 않고, 팬아웃이 구독하는 순간 실행된다
            client.fetchStayProducts(query)
                // 응답이 도착한 청크만 즉시 정규화 — 전체 응답을 기다리는 장벽이 없다
                .map<ChunkOutcome> { products ->
                    ChunkOutcome.Success(client.supplier, toStayProducts(client.supplier, products, plan.lookup, stayDates))
                }
                // 실패를 값(Failure)으로 바꿔야 flatMap 이 스트림을 죽이지 않는다 — 폭발 반경은 청크 하나.
                // Exception 만 흡수한다 — Error 계열(JVM 치명 상태)은 부분 실패로 위장시키지 않고 그대로 전파
                .onErrorResume(Exception::class.java) { e ->
                    val reason = if (e is SupplierCallException) {
                        log.warn("stay product chunk failed: supplier={} reason={}", client.supplier, e.reason)
                        e.reason
                    } else {
                        // 공급사 실패가 아닌 내부 예외(불변식 위반 등) — 상세는 로그에만 남기고,
                        // 공개 reason 에는 내부 구현을 흘리지 않는다 (docs/API.md 의 분류 문자열 규정)
                        log.error("unexpected failure while processing chunk: supplier={}", client.supplier, e)
                        "internal error"
                    }
                    // 이미 만들어 둔 값을 Mono 로 포장만 한다 — onErrorResume 은 대체 publisher 를 요구한다
                    Mono.just(ChunkOutcome.Failure(client.supplier, reason))
                }
        }

    /**
     * 정규화 — 중간 표준 타입을 표준 [StayProduct] 로 조립한다 (docs/ARCHITECTURE.md 의 두 번째 변환).
     * 코드 치환과 미매핑 스킵은 [MappingLookup.resolve] 가, 가용성 판정과 요금 계산은 도메인 정책이
     * 맡으므로 여기서는 결과를 조합하기만 한다. 미확정도 그대로 담는다 — 응답에서의 제외는 웹 계층의
     * 노출 정책이다.
     */
    private fun toStayProducts(
        supplier: Supplier,
        products: List<SupplierStayProduct>,
        lookup: MappingLookup,
        stayDates: List<LocalDate>,
    ): List<StayProduct> = products.mapNotNull { product ->
        val (property, roomType) = lookup.resolve(product) ?: return@mapNotNull null
        StayProduct(
            property = property,
            roomType = roomType,
            breakfastIncluded = product.breakfastIncluded,
            availability = AvailabilityPolicy.judge(stayDates, product.remainingByDate),
            supplier = supplier,
            price = Price.of(
                totalAmount = product.grossTotalAmount,
                nights = stayDates.size,
                currency = product.currency,
            ),
        )
    }

    private sealed interface ChunkOutcome {
        data class Success(val supplier: Supplier, val products: List<StayProduct>) : ChunkOutcome
        data class Failure(val supplier: Supplier, val reason: String) : ChunkOutcome
    }

    companion object {
        private const val MAX_CODES_PER_CALL = 50
    }
}
