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

            response.requireSuccessData(PROPERTIES_ENDPOINT)
                .items.map { it.toSupplierProperty() }
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
                response.requireSuccessData(SEARCH_ENDPOINT).items.map { it.toStayProduct() }
            }
            .onErrorMap { toSupplierError(supplier, SEARCH_ENDPOINT, it) }

    /**
     * HTTP 200 이어도 본문 resultCode 로 실패를 판정하고 (실패 판정 통일), 성공이면 data 를 꺼낸다.
     * 성공 코드인데 data 가 없는 응답은 빈 결과가 아니라 계약 위반이다 — 정상 빈 결과는 data 안의 빈
     * items 로 오므로, 조용히 빈 리스트로 삼키면 깨진 응답과 진짜 빈 결과를 구분할 수 없게 된다.
     * 실패면 [SupplierCallException] 을 던진다 — 리액티브 경로에서는 map 안에서 던져져 onError 로 전파된다.
     */
    private fun <T> SupplierBBaseResponse<T>.requireSuccessData(endpoint: String): T {
        if (resultCode != SupplierBBaseResponse.SUCCESS_CODE) {
            // resultCode 는 HTTP 상태를 미러링한다 (E503 → 503) — 숫자로 변환해 공통 분류 규칙을 그대로 쓴다.
            // 미러 형식이 아닌 알 수 없는 코드는 보수적으로 재시도 제외.
            val status = resultCode.removePrefix("E").toIntOrNull()
            throw SupplierCallException(
                supplier, "$endpoint resultCode=$resultCode",
                retryable = status != null && isRetryableStatus(status),
            )
        }
        return data ?: throw SupplierCallException(supplier, "$endpoint: success without data")
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
