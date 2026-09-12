package com.staysync.supplier.b

import com.staysync.config.SupplierProperties
import com.staysync.domain.model.Supplier
import com.staysync.observability.SupplierMetrics
import com.staysync.supplier.supplierErrorOfStatus
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
 * Supplier B 어댑터.
 * - 실패 판정 통일: HTTP 는 항상 200 이므로, 본문 resultCode 가 "0000"이 아니면 실패로 간주하고
 *   [SupplierCallException] 을 던진다. 이렇게 A 의 4xx/5xx 와 동일하게 다뤄진다.
 * - 요금 변환: totalPrice 가 이미 gross 총액이라 그대로 사용.
 * - 중복 날짜 방어: 같은 날짜의 잔여가 두 번 오면 어느 쪽이 진실인지 알 수 없다 — 그 항목만 제외한다
 *   (보수 원칙). 공급사 전체 응답은 죽이지 않는다.
 * - 값 결함 관문: 정규화·조립의 불변식이 요구하는 조건(가격·통화·재고·정원)을 [ConversionGate] 로
 *   response 를 내놓기 전에 선제 검증하고, 결함 항목만 제외한다 (docs/QUARANTINE.md).
 */
@Component
class SupplierBClient(
    @param:Qualifier("supplierBWebClient") private val webClient: WebClient,
    private val properties: SupplierProperties,
    private val metrics: SupplierMetrics,
) : SupplierClient {
    override val supplier = Supplier.B

    override fun fetchProperties(): List<SupplierProperty> =
        try {
            val response = webClient.get()
                .uri(PROPERTIES_ENDPOINT)
                // 동기화는 배치라 검색용 기본보다 관대한 응답 타임아웃을 요청 단위로 덮어쓴다 (docs/INTEGRATION.md)
                .httpRequest { request ->
                    request.getNativeRequest<HttpClientRequest>()
                        .responseTimeout(Duration.ofMillis(properties.syncResponseTimeoutMs))
                }
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
                response.requireSuccessData(SEARCH_ENDPOINT).items.mapNotNull { item ->
                    // 전체 격리 기록 자리(미구현, docs/QUARANTINE.md) — 원시 페이로드 보존은 어댑터만 안다:
                    // quarantineRecorder.record(supplier, rawPayload = item, requestContext = query)
                    // 사유별 카운터는 onDefect 로 기록한다 — 검색 경로 결함이 대시보드에 보이게
                    ConversionGate.admit(supplier, rawDates = item.inventory.map { it.date }, product = item.toStayProduct()) {
                        metrics.recordQuarantined(supplier, it)
                    }
                }
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
            // resultCode 는 HTTP 상태를 미러링한다 (E503 → 503) — 여기서는 상태 추출만 하고,
            // 분류(재시도·한도 초과)는 공통 팩토리에 위임한다. 미러 형식이 아닌 알 수 없는 코드는 null.
            val status = resultCode.takeIf { it.startsWith("E") }?.removePrefix("E")?.toIntOrNull()
            throw supplierErrorOfStatus(supplier, "$endpoint resultCode=$resultCode", status)
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
        grossTotalAmount = totalPrice.toBigDecimal(),
        remainingByDate = inventory.associate { it.date to it.remainingRooms },
    )

    companion object {
        private const val PROPERTIES_ENDPOINT = "/b/api/properties"
        private const val SEARCH_ENDPOINT = "/b/api/search"

        private val ISO: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE
    }
}
