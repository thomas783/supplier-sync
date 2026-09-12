package com.staysync.search

import com.staysync.config.SupplierProperties
import com.staysync.domain.model.AvailabilityPolicy
import com.staysync.resilience.SupplierResilience
import io.github.resilience4j.circuitbreaker.CallNotPermittedException
import com.staysync.domain.model.Price
import com.staysync.domain.model.StayProduct
import com.staysync.domain.model.Supplier
import com.staysync.observability.SupplierMetrics
import com.staysync.supplier.StayProductQuery
import com.staysync.supplier.SupplierCallException
import com.staysync.supplier.SupplierClient
import com.staysync.supplier.SupplierStayProduct
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.time.Duration
import java.time.LocalDate

/**
 * 통합 검색 유스케이스 (흐름의 규칙은 docs/ARCHITECTURE.md).
 *
 * 매핑에서 공급사별 보유 숙소 코드를 미리 로드(readOnly, 원격 호출 전에 종료) → 50개씩 청킹 → 여러
 * 공급사·청크를 논블로킹으로 병렬 조회 → 응답을 표준 모델로 정규화 → 병합. 청크 하나의 실패는
 * [ChunkOutcome.Failure] 로 흡수돼 스트림을 죽이지 않는다 — 한 공급사가 실패해도 나머지 결과로 응답하고
 * 실패 사실은 errors 로 드러난다.
 *
 * 팬아웃은 **공급사별로 격리**된다 — 공급사 그룹끼리는 병렬로 흐르되 한 공급사의 청크 동시성은 그 공급사의
 * 상한(bulkhead 설정이 단일 원천, [SupplierResilience.searchConcurrency])까지만 열려, 한 공급사가 동시성
 * 슬롯을 독점해 다른 공급사를 굶기지 못한다. 그리고 전체에 e2e 데드라인([searchDeadline])을 걸어, 느린
 * 공급사가 응답을 무한정 끌지 않게
 * 한다 — 데드라인 시점에 도착한 결과는 유지하고 미완 공급사는 TIMEOUT 으로 채운다(부분 응답).
 */
@Service
class StaySearchService(
    private val clients: List<SupplierClient>,
    private val mappingQueryService: MappingQueryService,
    private val resilience: SupplierResilience,
    private val metrics: SupplierMetrics,
    supplierProperties: SupplierProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val searchDeadline = Duration.ofMillis(supplierProperties.searchDeadlineMs)

    fun search(criteria: StaySearchCriteria): StaySearchResult {
        val stayDates = criteria.stayDates()

        // DB 읽기 국면 — 원격 호출 전에 매핑 읽기(readOnly 트랜잭션)를 전부 끝내고, 공급사별로 아직 실행되지
        // 않은 cold Mono 작업 목록만 손에 남긴다. 공급사별로 묶어 두어야 뒤의 팬아웃에서 동시성을 격리한다
        val tasksBySupplier: Map<Supplier, List<Mono<ChunkOutcome>>> = clients.mapNotNull { client ->
            val plan = mappingQueryService.loadPlan(client.supplier) ?: return@mapNotNull null
            client.supplier to buildTasks(client, plan, criteria, stayDates)
        }.toMap()
        // 매핑이 비어 있으면 오류가 아니라 "결과 없음"이다 (docs/API.md)
        if (tasksBySupplier.isEmpty()) return StaySearchResult(emptyList(), emptyList())

        val outcomes = Flux.fromIterable(tasksBySupplier.entries)
            // 공급사 그룹은 서로 병렬로 흐르되(바깥 flatMap), 한 공급사의 청크는 그 공급사의 동시성 상한까지만
            // 동시에 구독된다(안쪽 flatMap = 논블로킹 큐잉). 상한은 bulkhead 설정이 단일 원천이다
            // (resilience.searchConcurrency). 이 공급사별 격리가 bulkhead 의 실질이다 — 한 공급사가 슬롯을
            // 독점해도 다른 공급사의 동시성은 줄지 않는다. 실패는 이미 Failure 값이라 형제 청크를 취소시키는
            // 에러가 흐르지 않는다
            .flatMap({ (supplier, monos) ->
                Flux.fromIterable(monos).flatMap({ it }, resilience.searchConcurrency(supplier))
            }, tasksBySupplier.size)
            // e2e 데드라인 — 이 시점까지 도착한 outcome 만 취하고 미완 청크는 취소한다(스레드·커넥션 해제).
            // collectList().timeout() 은 도착분까지 버리므로, 도착분을 보존하는 take(deadline) 를 쓴다
            .take(searchDeadline)
            // 모든 청크가 결론에 이르거나 데드라인이 끊을 때까지 모으는 의도된 장벽
            .collectList()
            // 논블로킹 팬아웃이 값으로 수렴하는 유일한 지점 — MVC 컨트롤러가 동기 호출하는 경계다
            .block()
            // collectList 는 항상 리스트를 방출하지만 block() 의 반환 타입은 nullable — !! 대신 빈 결과로
            .orEmpty()

        // 여기부터는 리액티브가 끝난 일반 컬렉션 조작 — sealed 타입으로 성공/실패를 가른다
        val stays = outcomes.filterIsInstance<ChunkOutcome.Success>().flatMap { it.products }
        val failures = outcomes.filterIsInstance<ChunkOutcome.Failure>()
            .map { SupplierError(it.supplier, it.reason) }
        // 데드라인 안에 결론에 이른 outcome 수가 기대 청크 수보다 적으면 그 공급사는 미완 — TIMEOUT 으로
        // 채운다. 도착한 청크의 성공분은 그대로 남으므로 부분 응답이다(한 공급사가 결과와 TIMEOUT 을 함께
        // 가질 수 있다). 실패(Failure)로 결론난 청크는 도착한 것이라 미완에 포함되지 않는다
        val completedBySupplier = outcomes.groupingBy { it.supplier }.eachCount()
        val timedOut = tasksBySupplier
            .filter { (supplier, monos) -> completedBySupplier.getOrDefault(supplier, 0) < monos.size }
            .map { (supplier, _) -> SupplierError(supplier, "search deadline exceeded") }
        // 같은 공급사의 여러 청크가 같은 사유로 실패하면 하나로 합친다
        val errors = (failures + timedOut).distinct()
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
            // cold Mono — 여기서는 HTTP 가 나가지 않고, 팬아웃이 구독하는 순간 실행된다.
            // 재시도·서킷은 원격 호출에만 입힌다 — 뒤의 정규화(map)에서 터지는 내부 예외는 재시도 대상도,
            // 공급사 실패 창에 기록될 일도 아니기 때문이다
            resilience.decorate(client.supplier, client.fetchStayProducts(query))
                // 응답이 도착한 청크만 즉시 정규화 — 전체 응답을 기다리는 장벽이 없다
                .map<ChunkOutcome> { products ->
                    ChunkOutcome.Success(client.supplier, toStayProducts(client.supplier, products, plan.lookup, stayDates))
                }
                // 실패를 값(Failure)으로 바꿔야 flatMap 이 스트림을 죽이지 않는다 — 폭발 반경은 청크 하나.
                // Exception 만 흡수한다 — Error 계열(JVM 치명 상태)은 부분 실패로 위장시키지 않고 그대로 전파
                .onErrorResume(Exception::class.java) { e ->
                    val reason = when (e) {
                        is SupplierCallException -> {
                            log.warn("stay product chunk failed: supplier={} reason={}", client.supplier, e.reason)
                            e.reason
                        }
                        // 서킷이 차단한 호출 — 원격으로 나가지도 않았으므로 사유를 별도 분류로 남긴다
                        is CallNotPermittedException -> {
                            log.warn("stay product chunk blocked: supplier={} reason=circuit open", client.supplier)
                            "circuit open"
                        }
                        else -> {
                            // 공급사 실패가 아닌 내부 예외(불변식 위반 등) — 상세는 로그에만 남기고,
                            // 공개 reason 에는 내부 구현을 흘리지 않는다 (docs/API.md 의 분류 문자열 규정)
                            log.error("unexpected failure while processing chunk: supplier={}", client.supplier, e)
                            "internal error"
                        }
                    }
                    // 이미 만들어 둔 값을 Mono 로 포장만 한다 — onErrorResume 은 대체 publisher 를 요구한다
                    Mono.just(ChunkOutcome.Failure(client.supplier, reason))
                }
        }

    /**
     * 정규화 — 중간 표준 타입을 표준 [StayProduct] 로 조립한다 (docs/ARCHITECTURE.md 의 두 번째 변환).
     * 코드 치환과 미매핑 스킵은 [MappingLookup.resolve] 가, 가용성 판정과 요금 계산은 도메인 정책이
     * 맡으므로 여기서는 결과를 조합하기만 한다. 미확정(judge = null)은 여기서 제외된다 — 표준 모델에
     * 도달하는 가용성은 언제나 확정 상태다(미매핑 제외와 같은 층위).
     */
    private fun toStayProducts(
        supplier: Supplier,
        products: List<SupplierStayProduct>,
        lookup: MappingLookup,
        stayDates: List<LocalDate>,
    ): List<StayProduct> = products.mapNotNull { product ->
        val (property, roomType) = lookup.resolve(product) ?: return@mapNotNull null
        val availability = AvailabilityPolicy.judge(stayDates, product.remainingByDate)
        // 판정 분포 기록 — 보수적 노출 정책이 조용히 빼는 상품의 규모를 정량화한다 (docs/MONITORING.md)
        metrics.recordAvailability(supplier, availability)
        if (availability == null) return@mapNotNull null
        StayProduct(
            property = property,
            roomType = roomType,
            breakfastIncluded = product.breakfastIncluded,
            availability = availability,
            supplier = supplier,
            price = Price.of(
                totalAmount = product.grossTotalAmount,
                nights = stayDates.size,
                currency = product.currency,
            ),
        )
    }

    private sealed interface ChunkOutcome {
        val supplier: Supplier
        data class Success(override val supplier: Supplier, val products: List<StayProduct>) : ChunkOutcome
        data class Failure(override val supplier: Supplier, val reason: String) : ChunkOutcome
    }

    companion object {
        private const val MAX_CODES_PER_CALL = 50
    }
}
