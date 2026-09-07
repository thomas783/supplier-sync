package com.staysync.supplier.a

import com.staysync.config.SupplierProperties
import com.staysync.domain.model.Supplier
import com.staysync.supplier.toSupplierError
import com.staysync.supplier.StayProductQuery
import com.staysync.supplier.SupplierCallException
import com.staysync.supplier.SupplierClient
import com.staysync.supplier.SupplierProperty
import com.staysync.supplier.SupplierRoomType
import com.staysync.supplier.SupplierStayProduct
import org.slf4j.LoggerFactory
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
 */
@Component
class SupplierAClient(
    @param:Qualifier("supplierAWebClient") private val webClient: WebClient,
    private val properties: SupplierProperties,
) : SupplierClient {
    private val log = LoggerFactory.getLogger(javaClass)

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
            .map { response -> response.items.mapNotNull { it.toStayProductOrNull() } }
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

    private fun SupplierAAvailabilityItem.toStayProductOrNull(): SupplierStayProduct? {
        if (dailyRates.size != dailyRates.distinctBy { it.date }.size) {
            log.warn("skipping item with duplicate dates: supplier={} hotelCode={} roomTypeCode={}", supplier, hotelCode, roomTypeCode)
            return null
        }
        return SupplierStayProduct(
            supplierPropertyCode = hotelCode,
            propertyName = hotelName,
            supplierRoomTypeCode = roomTypeCode,
            roomTypeName = roomTypeName,
            maxOccupancy = maxOccupancy,
            breakfastIncluded = breakfastIncluded,
            currency = currency,
            // 세금 별도(net) → gross 총액 = Σ(nightlyRate + taxAmount)
            grossTotalAmount = dailyRates.sumOf { it.nightlyRate + it.taxAmount },
            remainingByDate = dailyRates.associate { it.date to it.remainingRooms },
        )
    }

    companion object {
        private const val HOTELS_ENDPOINT = "/a/v1/hotels"
        private const val AVAILABILITY_ENDPOINT = "/a/v1/availability"

        private val ISO: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE
    }
}
