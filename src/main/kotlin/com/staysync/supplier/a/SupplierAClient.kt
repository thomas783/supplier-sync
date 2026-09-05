package com.staysync.supplier.a

import com.staysync.domain.model.Supplier
import com.staysync.supplier.toSupplierError
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
import java.time.format.DateTimeFormatter

/**
 * Supplier A 어댑터.
 * - 실패 판정: HTTP 4xx/5xx → [SupplierCallException]. 5xx·429 는 재시도 가능으로 분류.
 * - 요금 변환: 날짜별 (nightlyRate + taxAmount) 를 합산해 gross 총액으로. 일자별 gross 는
 *   실측이므로 nightlyAmountsByDate 에 보존한다.
 */
@Component
class SupplierAClient(
    @param:Qualifier("supplierAWebClient") private val webClient: WebClient,
) : SupplierClient {

    override val supplier = Supplier.A

    override fun fetchProperties(): List<SupplierProperty> =
        try {
            webClient.get()
                .uri(HOTELS_ENDPOINT)
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
            .map { response -> response.items.map { it.toStayProduct() } }
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

    private fun SupplierAAvailabilityItem.toStayProduct(): SupplierStayProduct {
        // 세금 별도(net) → gross 총액 = Σ(nightlyRate + taxAmount). 일자별 gross 는 실측으로 보존.
        val nightlyGrossByDate = dailyRates.associate { it.date to (it.nightlyRate + it.taxAmount) }
        return SupplierStayProduct(
            supplierPropertyCode = hotelCode,
            propertyName = hotelName,
            supplierRoomTypeCode = roomTypeCode,
            roomTypeName = roomTypeName,
            maxOccupancy = maxOccupancy,
            breakfastIncluded = breakfastIncluded,
            currency = currency,
            grossTotalAmount = nightlyGrossByDate.values.sum(),
            nightlyAmountsByDate = nightlyGrossByDate,
            remainingByDate = dailyRates.associate { it.date to it.remainingRooms },
        )
    }

    companion object {
        private const val HOTELS_ENDPOINT = "/a/v1/hotels"
        private const val AVAILABILITY_ENDPOINT = "/a/v1/availability"

        private val ISO: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE
    }
}
