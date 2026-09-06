package com.staysync.web

import jakarta.validation.Validation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate

/**
 * 요청 DTO 선언 규칙의 단위 테스트 — Spring 없이 표준 Validator 로 검증한다.
 * 웹 왕복(바인딩 + 핸들러의 메시지 변환)은 StaySearchIntegrationTest 가 담당하고,
 * 여기서는 제약 선언 자체의 의미(특히 규칙 사이의 책임 분리)를 고정한다.
 */
class StaySearchRequestTest {

    private val validator = Validation.buildDefaultValidatorFactory().validator

    private fun request(
        checkIn: LocalDate? = LocalDate.of(2026, 9, 1),
        checkOut: LocalDate? = LocalDate.of(2026, 9, 4),
        adults: Int? = 2,
        children: Int = 0,
    ) = StaySearchRequest(checkIn = checkIn, checkOut = checkOut, adults = adults, children = children)

    @Test
    fun `정상 요청 - 위반이 없다`() {
        assertTrue(validator.validate(request()).isEmpty())
    }

    @Test
    fun `같은 날 체크인아웃 - 0박이라 순서 규칙 위반이다`() {
        val violations = validator.validate(request(checkOut = LocalDate.of(2026, 9, 1)))

        assertEquals("checkOut은 checkIn보다 뒤여야 합니다", violations.single().message)
    }

    @Test
    fun `checkIn 누락 - 누락 위반 하나만 발생하고 순서 규칙은 기권한다`() {
        // periodValid 가 null 날짜에서 true(판정 보류)를 돌려줘야 한 문제에 한 메시지가 유지된다 —
        // 이 기권이 깨지면 누락 요청에 "순서가 잘못됐다"는 엉뚱한 사유가 섞인다
        val violations = validator.validate(request(checkIn = null))

        assertEquals("checkIn", violations.single().propertyPath.toString())
    }

    @Test
    fun `adults 0 - 하한 규칙의 선언된 메시지로 위반된다`() {
        val violations = validator.validate(request(adults = 0))

        assertEquals("adults는 1 이상이어야 합니다", violations.single().message)
    }

    @Test
    fun `toCriteria - 검증을 통과한 값이 그대로 도메인 입력으로 투영된다`() {
        val criteria = request().toCriteria()

        assertEquals(LocalDate.of(2026, 9, 1), criteria.checkIn)
        assertEquals(LocalDate.of(2026, 9, 4), criteria.checkOut)
        assertEquals(2, criteria.adults)
        assertEquals(0, criteria.children)
    }
}
