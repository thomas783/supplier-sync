package com.staysync.supplier.b

import com.staysync.domain.model.Supplier
import com.staysync.supplier.isRetryableStatus
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
 * Supplier B 어댑터.
 * - 실패 판정 통일: HTTP 는 항상 200 이므로, 본문 resultCode 가 "0000"이 아니면 실패로 간주하고
 *   [SupplierCallException] 을 던진다. 이렇게 A 의 4xx/5xx 와 동일하게 다뤄진다.
 * - 요금 변환: totalPrice 가 이미 gross 총액이라 그대로 사용. 일자별 실측 금액은 주지 않으므로
 *   nightlyAmountsByDate 는 빈 맵 — 평균을 복제해 채우지 않는다 (docs/DOMAIN_MODEL.md).
 */
@Component
class SupplierBClient(
    @param:Qualifier("supplierBWebClient") private val webClient: WebClient,
) : SupplierClient {

    override val supplier = Supplier.B

    override fun fetchProperties(): List<SupplierProperty> =
        try {
            val response = webClient.get()
                .uri(PROPERTIES_ENDPOINT)
                .retrieve()
                .bodyToMono<SupplierBBaseResponse<SupplierBPropertiesData>>()
                .block()
                ?: throw SupplierCallException(supplier, "$PROPERTIES_ENDPOINT: empty response")

            response.requireSuccess(PROPERTIES_ENDPOINT)
                .data?.items.orEmpty().map { it.toSupplierProperty() }
        } catch (e: Exception) {
            // 동기 경로의 실패 통일 지점 — 리액티브 경로의 onErrorMap 과 같은 변환기를 쓴다
            throw toSupplierError(supplier, PROPERTIES_ENDPOINT, e)
        }

    override fun fetchStayProducts(query: StayProductQuery): Mono<List<SupplierStayProduct>> =
        webClient.get()
            .uri { builder ->
                builder.path(SEARCH_ENDPOINT)
                    .queryParam("propertyIds", query.propertyCodes.joinToString(","))
                    .queryParam("checkIn", query.checkIn.format(ISO))
                    .queryParam("checkOut", query.checkOut.format(ISO))
                    .queryParam("adults", query.adults)
                    .queryParam("children", query.children)
                    .build()
            }
            .retrieve()
            .bodyToMono<SupplierBBaseResponse<SupplierBSearchData>>()
            .map { response ->
                response.requireSuccess(SEARCH_ENDPOINT).data?.items.orEmpty().map { it.toStayProduct() }
            }
            .onErrorMap { toSupplierError(supplier, SEARCH_ENDPOINT, it) }

    /**
     * HTTP 200 이어도 본문 resultCode 로 실패를 판정한다 (실패 판정 통일).
     * 실패면 [SupplierCallException] 을 던진다 — 리액티브 경로에서는 map 안에서 던져져 onError 신호로 전파된다.
     */
    private fun <T> SupplierBBaseResponse<T>.requireSuccess(endpoint: String): SupplierBBaseResponse<T> {
        if (resultCode != SupplierBBaseResponse.SUCCESS_CODE) {
            // resultCode 는 HTTP 상태를 미러링한다 (E503 → 503) — 숫자로 변환해 공통 분류 규칙을 그대로 쓴다.
            // 미러 형식이 아닌 알 수 없는 코드는 보수적으로 재시도 제외.
            val status = resultCode.removePrefix("E").toIntOrNull()
            throw SupplierCallException(
                supplier, "$endpoint resultCode=$resultCode",
                retryable = status != null && isRetryableStatus(status),
            )
        }
        return this
    }

    private fun SupplierBProperty.toSupplierProperty(): SupplierProperty = SupplierProperty(
        supplierPropertyCode = propertyId,
        propertyName = propertyName,
        roomTypes = rooms.map {
            SupplierRoomType(
                supplierRoomTypeCode = it.roomId,
                roomTypeName = it.roomName,
                maxOccupancy = it.maxOccupancy,
            )
        },
    )

    private fun SupplierBSearchItem.toStayProduct(): SupplierStayProduct = SupplierStayProduct(
        supplierPropertyCode = propertyId,
        propertyName = propertyName,
        supplierRoomTypeCode = roomId,
        roomTypeName = roomName,
        maxOccupancy = maxOccupancy,
        breakfastIncluded = breakfastIncluded,
        currency = currency,
        grossTotalAmount = totalPrice,
        nightlyAmountsByDate = emptyMap(),
        remainingByDate = inventory.associate { it.date to it.remainingRooms },
    )

    companion object {
        private const val PROPERTIES_ENDPOINT = "/b/api/properties"
        private const val SEARCH_ENDPOINT = "/b/api/search"

        private val ISO: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE
    }
}
