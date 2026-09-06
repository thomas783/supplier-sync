package com.staysync.web

import com.staysync.search.StaySearchCriteria
import jakarta.validation.constraints.AssertTrue
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotNull
import org.springframework.format.annotation.DateTimeFormat
import java.time.LocalDate

/**
 * 통합 검색 요청 (docs/API.md 의 400 규칙이 선언으로 담긴 곳).
 *
 * 검증은 Bean Validation 이 수행하고, 위반의 응답 변환은 [GlobalExceptionHandler] 가 일괄 담당한다 —
 * 컨트롤러에는 검증 분기가 남지 않는다. 필수 파라미터를 nullable 로 두는 이유는 누락을 바인딩 실패가
 * 아니라 @NotNull 위반으로 받아 같은 400 포맷으로 흘리기 위해서다.
 *
 * 프로퍼티가 var 인 이유: val 도 Spring 6.1+ 의 생성자 바인딩으로 동작하지만, "@NotNull 프로퍼티는
 * mutable 이어야 한다"는 IDE 경고(세터 바인딩 시절의 전제)가 남는다. 이 클래스는 도메인 값 객체가 아니라
 * 바인딩 경계의 배관이라 가변의 실질 비용이 없어 경고 없는 쪽을 택했다 — 불변성은 검증 직후
 * [toCriteria] 가 불변 도메인 입력으로 투영하며 회복된다.
 */
data class StaySearchRequest(
    @field:NotNull
    @field:DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    var checkIn: LocalDate? = null,

    @field:NotNull
    @field:DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    var checkOut: LocalDate? = null,

    // 성인 미동반 숙박은 법령·업계 관행상 불가하다는 도메인 규칙
    @field:NotNull
    @field:Min(value = 1, message = "adults는 1 이상이어야 합니다")
    var adults: Int? = null,

    @field:Min(value = 0, message = "children은 0 이상이어야 합니다")
    var children: Int = 0,
) {
    // 교차 필드 규칙 — 체크아웃일은 숙박일에 포함되지 않으므로 같은 날은 0박이라 성립 불가.
    // 날짜가 없으면 판정을 보류하고 통과시킨다 — 누락의 400 은 @NotNull 이 담당하므로 위반이 겹치지 않는다
    @get:AssertTrue(message = "checkOut은 checkIn보다 뒤여야 합니다")
    val periodValid: Boolean
        get() {
            val start = checkIn ?: return true
            val end = checkOut ?: return true
            return end.isAfter(start)
        }

    /** 검증을 통과한 뒤에만 호출된다 — 필수 값의 null 은 이 시점엔 불가능하다. */
    fun toCriteria(): StaySearchCriteria = StaySearchCriteria(
        checkIn = requireNotNull(checkIn),
        checkOut = requireNotNull(checkOut),
        adults = requireNotNull(adults),
        children = children,
    )
}
