package com.staysync.supplier.a

import com.staysync.config.SupplierProperties
import com.staysync.domain.model.Supplier
import com.staysync.observability.SupplierMetrics
import com.staysync.supplier.toSupplierError
import com.staysync.supplier.ConversionGate
import com.staysync.supplier.StayProductQuery
import com.staysync.supplier.SupplierCallException
import com.staysync.supplier.SupplierClient
import com.staysync.supplier.SupplierProperty
import com.staysync.supplier.SupplierRoomType
import com.staysync.supplier.SupplierStayProduct
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.bodyToMono
import reactor.core.publisher.Mono
import reactor.netty.http.client.HttpClientRequest
import java.time.Duration
import java.time.format.DateTimeFormatter

/**
 * Supplier A 어댑터.
 * - 실패 판정: HTTP 4xx/5xx → [SupplierCallException]. 5xx·429 는 재시도 가능으로 분류.
 * - 요금 변환: 날짜별 (nightlyRate + taxAmount) 를 합산해 gross 총액으로.
 * - 중복 날짜 방어: 같은 날짜가 두 번 오면 총액이 이중 합산되고 잔여 수는 임의의 값이 된다 — 정답을
 *   추측할 수 없으므로 그 항목만 제외한다(보수 원칙). 공급사 전체 응답은 죽이지 않는다.
 * - 값 결함 관문: 정규화·조립의 불변식이 요구하는 조건(가격·통화·재고·정원)을 [ConversionGate] 로
 *   response 를 내놓기 전에 선제 검증하고, 결함 항목만 제외한다 (docs/QUARANTINE.md).
 */
@Component
class SupplierAClient(
    @param:Qualifier("supplierAWebClient") private val webClient: WebClient,
    private val properties: SupplierProperties,
    private val metrics: SupplierMetrics,
) : SupplierClient {
    override val supplier = Supplier.A

    override fun fetchProperties(): List<SupplierProperty> =
        try {
            webClient.get()
                .uri(HOTELS_ENDPOINT)
                // 동기화는 배치라 검색용 기본보다 관대한 응답 타임아웃을 요청 단위로 덮어쓴다 (docs/INTEGRATION.md)
                .httpRequest { request ->
                    request.getNativeRequest<HttpClientRequest>()
                        .responseTimeout(Duration.ofMillis(properties.syncResponseTimeoutMs))
                }
                .retrieve()
                .bodyToMono<SupplierABaseResponse<SupplierAHotel>>()
                .block()
                ?.items
                ?.map { it.toSupplierProperty() }
                // 본문 없는 200 은 "숙소 0건"이 아니라 계약 위반 — 정상 빈 응답은 items: [] 로 온다
                ?: throw SupplierCallException(supplier, "$HOTELS_ENDPOINT: empty response")
        } catch (e: Exception) {
            // 동기 경로의 실패 통일 지점 — 리액티브 경로의 onErrorMap 과 같은 변환기를 쓴다
            throw toSupplierError(supplier, HOTELS_ENDPOINT, e)
        }

    override fun fetchStayProducts(query: StayProductQuery): Mono<List<SupplierStayProduct>> =
        webClient.get()
            .uri { builder ->
                builder.path(AVAILABILITY_ENDPOINT)
                    .queryParam("hotelCodes", query.propertyCodes.joinToString(","))
                    .queryParam("checkIn", query.checkIn.format(ISO))
                    .queryParam("checkOut", query.checkOut.format(ISO))
                    .queryParam("adults", query.adults)
                    .queryParam("children", query.children)
                    .build()
            }
            .retrieve()
            .bodyToMono<SupplierABaseResponse<SupplierAAvailabilityItem>>()
            .map { response ->
                val stayDates = query.stayDates()
                response.items.mapNotNull { item ->
                    // 요청 기간의 날짜만 남긴다 — 총액이 요청 박수와 무관하게 부풀지 않게, 가용성 판정이
                    // 기간 밖 날짜를 무시하는 것과 같은 기준(docs/DOMAIN_MODEL.md 예약 가능 판정)
                    val rates = item.dailyRates.filter { it.date in stayDates }
                    // 결함 판정은 관문에 맡긴다 — 중복 날짜(rawDates)·일자별 음수 요금(rawAmounts)처럼 합산·
                    // 병합으로 사라지는 원자료를 함께 넘긴다. 전체 격리 기록 자리(미구현, docs/QUARANTINE.md):
                    // quarantineRecorder.record(supplier, rawPayload = item, requestContext = query)
                    ConversionGate.admit(
                        supplier,
                        rawDates = rates.map { it.date },
                        product = item.toStayProduct(rates),
                        rawAmounts = rates.flatMap { listOf(it.nightlyRate, it.taxAmount) },
                    ) { metrics.recordQuarantined(supplier, it, "search") }
                }
            }
            .onErrorMap { toSupplierError(supplier, AVAILABILITY_ENDPOINT, it) }

    private fun SupplierAHotel.toSupplierProperty(): SupplierProperty = SupplierProperty(
        supplierPropertyCode = hotelCode,
        propertyName = hotelName,
        roomTypes = roomTypes.map {
            SupplierRoomType(
                supplierRoomTypeCode = it.roomTypeCode,
                roomTypeName = it.roomTypeName,
                maxOccupancy = it.maxOccupancy,
            )
        },
    )

    // 요청 기간으로 필터링된 dailyRates(rates)로 조립한다 — 총액·잔여 모두 요청 숙박일 기준
    private fun SupplierAAvailabilityItem.toStayProduct(rates: List<SupplierADailyRate>): SupplierStayProduct = SupplierStayProduct(
        supplierPropertyCode = hotelCode,
        propertyName = hotelName,
        supplierRoomTypeCode = roomTypeCode,
        roomTypeName = roomTypeName,
        maxOccupancy = maxOccupancy,
        breakfastIncluded = breakfastIncluded,
        currency = currency,
        // 세금 별도(net) → gross 총액 = Σ(nightlyRate + taxAmount)
        grossTotalAmount = rates.sumOf { it.nightlyRate + it.taxAmount },
        remainingByDate = rates.associate { it.date to it.remainingRooms },
    )

    companion object {
        private const val HOTELS_ENDPOINT = "/a/v1/hotels"
        private const val AVAILABILITY_ENDPOINT = "/a/v1/availability"

        private val ISO: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE
    }
}
