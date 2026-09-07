package com.staysync.supplier

import com.staysync.domain.model.Supplier
import io.netty.channel.ConnectTimeoutException
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Positive
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

/**
 * 숙소 목록 조회 결과 (정적 콘텐츠). 요금·재고는 없다.
 * 애노테이션은 공급사 계약의 선언이다 — 동기화가 저장 전에 Validator 로 판정해 계약 밖 레코드를 건너뛴다.
 */
data class SupplierProperty(
    @field:NotBlank val supplierPropertyCode: String,
    @field:NotBlank val propertyName: String,
    val roomTypes: List<SupplierRoomType>,
)

data class SupplierRoomType(
    @field:NotBlank val supplierRoomTypeCode: String,
    @field:NotBlank val roomTypeName: String,
    @field:Positive val maxOccupancy: Int,
)

/**
 * 재고·요금 조회 결과 1건 — 공급사 코드 기반의 숙박 상품 (숙소 × 객실 타입 단위).
 *
 * 요금은 두 공급사 교집합인 "세금 포함 총액(gross)"으로 이미 통일해 담는다
 * (A: Σ(nightlyRate + taxAmount), B: totalPrice).
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
    /** 호출 한도 초과(429/E429) — 짧은 재시도는 한도를 더 두드리므로 재시도 대기를 길게 가져간다. */
    val rateLimited: Boolean = false,
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
        supplierErrorOfStatus(supplier, "$endpoint HTTP $code", code, cause = t)
    }
    // 무응답 — 응답 타임아웃(5초)에 끊긴 경우. 일시 장애로 보고 재시도 가능
    isTimeout(t) ->
        SupplierCallException(supplier, "$endpoint timeout (no response)", retryable = true, cause = t)
    // 그 외 (연결 실패, 역직렬화 오류 등) — 원인 불명은 보수적으로 재시도 제외
    else -> SupplierCallException(supplier, "$endpoint call failed: ${t.message}", retryable = false, cause = t)
}

/**
 * 상태 코드 기반 분류의 유일한 지점 — 숫자 상태에서 재시도 가능 여부와 한도 초과 여부를 파생해 통일
 * 예외를 만든다. HTTP 상태 코드뿐 아니라 그것을 미러링하는 B 의 resultCode(E503 → 503)도 같은 규칙을
 * 쓴다 — 어댑터에는 "자기 실패 표현에서 상태를 추출하는 지식"만 남고, 분류는 여기로 모인다.
 *
 * 기준은 "같은 요청을 다시 보내면 결과가 달라질 수 있는가"다 (근거 상세는 docs/INTEGRATION.md).
 * - 503·504: 약속된 일시성 → 재시도
 * - 500·502: 일시성의 약속은 없지만 실무에선 인스턴스 단위 순단(재시작·커넥션 순단)이 흔하다.
 *   결정적 오류였을 때의 하방은 1회 제한과 서킷으로 유계라 재시도 기대값이 양수 → 재시도
 * - 429 (호출 한도 초과): 한도 창 회복 후 성공 가능 → 재시도, 단 대기는 길게(rateLimited)
 * - 501 같은 결정적 5xx, 그 외 4xx(400 잘못된 요청, 401 인증 실패): 다시 보내도 같은 결과 → 제외
 * - 상태를 추출할 수 없으면(null — 미러 형식이 아닌 알 수 없는 코드 등) 보수적으로 제외
 *
 * 502·504는 공급사 계약 문서(400·401·429·500·503)에 없지만, 공급사 앞단 인프라(로드밸런서·게이트웨이)가
 * 만들어내는 전송 경로의 코드라 포함한다 — 계약에 없는 무응답 타임아웃을 다루는 것과 같은 층위다.
 */
internal fun supplierErrorOfStatus(
    supplier: Supplier,
    reason: String,
    status: Int?,
    cause: Throwable? = null,
): SupplierCallException = SupplierCallException(
    supplier, reason,
    retryable = status != null && isRetryableStatus(status),
    rateLimited = status == 429,
    cause = cause,
)

private val RETRYABLE_STATUSES = setOf(429, 500, 502, 503, 504)

private fun isRetryableStatus(status: Int): Boolean = status in RETRYABLE_STATUSES

/**
 * 무응답 계열 예외 식별 — 응답 타임아웃과 연결 타임아웃 모두. [toSupplierError] 안에서만 쓰인다.
 * Netty 의 [ConnectTimeoutException] 은 [TimeoutException] 이 아니라 ConnectException 계열이라 따로 명시한다.
 */
private fun isTimeout(t: Throwable): Boolean =
    t is ReadTimeoutException || t.cause is ReadTimeoutException ||
        t is ConnectTimeoutException || t.cause is ConnectTimeoutException ||
        t is TimeoutException
