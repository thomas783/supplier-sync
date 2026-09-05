package com.staysync.supplier

import com.staysync.domain.model.Supplier
import io.netty.handler.timeout.ReadTimeoutException
import org.springframework.web.reactive.function.client.WebClientResponseException
import java.time.LocalDate
import java.util.concurrent.TimeoutException

/**
 * 공급사 연동 계층의 중간 표준 타입 — 공급사 코드를 그대로 쓰되 형식은 통일 (docs/ARCHITECTURE.md).
 *
 * 각 공급사 어댑터는 자신의 원시 응답을 이 타입으로 변환한다. 이 경계 덕분에 공급사별 요청/응답 형식이
 * 도메인·검색 계층으로 새어 나가지 않고, 원시 DTO 는 supplier/a·supplier/b 패키지 안에 갇힌다.
 * 내부 id 로의 치환과 가용성 판정은 정규화(검색 계층)의 몫이다.
 */

/** 숙소 목록 조회 결과 (정적 콘텐츠). 요금·재고는 없다. */
data class SupplierProperty(
    val supplierPropertyCode: String,
    val propertyName: String,
    val roomTypes: List<SupplierRoomType>,
)

data class SupplierRoomType(
    val supplierRoomTypeCode: String,
    val roomTypeName: String,
    val maxOccupancy: Int,
)

/**
 * 재고·요금 조회 결과 1건 — 공급사 코드 기반의 숙박 상품 (숙소 × 객실 타입 단위).
 *
 * 요금은 두 공급사 교집합인 "세금 포함 총액(gross)"으로 이미 통일해 담는다
 * (A: Σ(nightlyRate + taxAmount), B: totalPrice).
 *
 * @property nightlyAmountsByDate 일자별 실측 금액(gross). 실측을 주는 공급사(A)만 채우고 없는
 *   공급사(B)는 빈 맵 — 평균을 복제해 채우지 않는다 (docs/DOMAIN_MODEL.md 의 nightlyRates 예외).
 */
data class SupplierStayProduct(
    val supplierPropertyCode: String,
    val propertyName: String,
    val supplierRoomTypeCode: String,
    val roomTypeName: String,
    val maxOccupancy: Int,
    val breakfastIncluded: Boolean,
    val currency: String,
    val grossTotalAmount: Long,
    val nightlyAmountsByDate: Map<LocalDate, Long>,
    val remainingByDate: Map<LocalDate, Int>,
)

/** 재고·요금 조회 요청. propertyCodes 는 한 호출당 최대 50개(공급사 제한, docs/INTEGRATION.md). */
data class StayProductQuery(
    val propertyCodes: List<String>,
    val checkIn: LocalDate,
    val checkOut: LocalDate,
    val adults: Int,
    val children: Int,
)

/**
 * 공급사 호출 실패. 실패 전달 방식(HTTP 상태 코드 vs 응답 본문 코드 vs 무응답 타임아웃)이 공급사마다
 * 다르지만, 어댑터가 이 하나의 예외로 통일해 던진다. 검색 계층은 이것을 잡아 부분 실패로 처리한다.
 *
 * @property retryable 일시적 실패로 재시도가 유효한지. 타임아웃·5xx·429·공급사 내부오류(E500/E503/E429)는
 *   true, 잘못된 요청·인증 실패(4xx/E400/E401)는 false. 재시도 정책 도입 시 이 값으로 대상을 가른다.
 */
class SupplierCallException(
    val supplier: Supplier,
    val reason: String,
    val retryable: Boolean = false,
    cause: Throwable? = null,
) : RuntimeException("supplier=$supplier reason=$reason", cause)

/**
 * 전송 계층 실패를 통일 예외로 변환한다 — HTTP 로 연동하는 모든 공급사에 공통인 분류라 어댑터 밖에 둔다.
 * 공급사 고유의 실패 표현(B 의 resultCode 등)은 각 어댑터가 이 변환 전에 [SupplierCallException] 으로
 * 만들어 두면 첫 분기에서 그대로 통과한다.
 */
internal fun toSupplierError(supplier: Supplier, endpoint: String, t: Throwable): SupplierCallException = when {
    // 이미 통일된 실패 (B 의 resultCode 실패 등) — 그대로 통과
    t is SupplierCallException -> t
    t is WebClientResponseException -> {
        val code = t.statusCode.value()
        SupplierCallException(supplier, "$endpoint HTTP $code", retryable = isRetryableStatus(code), cause = t)
    }
    // 무응답 — 응답 타임아웃(5초)에 끊긴 경우. 일시 장애로 보고 재시도 가능
    isTimeout(t) ->
        SupplierCallException(supplier, "$endpoint timeout (no response)", retryable = true, cause = t)
    // 그 외 (연결 실패, 역직렬화 오류 등) — 원인 불명은 보수적으로 재시도 제외
    else -> SupplierCallException(supplier, "$endpoint call failed: ${t.message}", retryable = false, cause = t)
}

/**
 * 상태 코드 기반 재시도 분류 — 유일한 판단 지점.
 * - 5xx (500 내부 오류, 503 일시 장애): 공급사 쪽 일시 문제 → 재시도 가능
 * - 429 (호출 한도 초과): 잠시 뒤 같은 요청이 성공할 수 있다 → 재시도 가능
 * - 그 외 4xx (400 잘못된 요청, 401 인증 실패): 요청 자체의 문제라 다시 보내도 같은 결과 → 재시도 무의미
 *
 * HTTP 상태 코드뿐 아니라, 그것을 미러링하는 B 의 resultCode(E503 → 503)도 같은 규칙을 쓴다.
 */
internal fun isRetryableStatus(status: Int): Boolean = status >= 500 || status == 429

/** 무응답(응답 타임아웃) 예외 식별. [toSupplierError] 안에서만 쓰인다. */
private fun isTimeout(t: Throwable): Boolean =
    t is ReadTimeoutException || t.cause is ReadTimeoutException || t is TimeoutException
