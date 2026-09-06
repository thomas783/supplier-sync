package com.staysync.search

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.LocalDate

class StaySearchCriteriaTest {

    @Test
    fun `숙박일은 체크인부터 체크아웃 전날까지 - 체크아웃일 미포함`() {
        val criteria = StaySearchCriteria(
            checkIn = LocalDate.of(2026, 9, 1),
            checkOut = LocalDate.of(2026, 9, 4),
            adults = 2,
            children = 0,
        )
        assertEquals(
            listOf(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 2), LocalDate.of(2026, 9, 3)),
            criteria.stayDates(),
        )
    }

    @Test
    fun `1박 - 숙박일은 체크인일 하루`() {
        val criteria = StaySearchCriteria(
            checkIn = LocalDate.of(2026, 9, 1),
            checkOut = LocalDate.of(2026, 9, 2),
            adults = 1,
            children = 0,
        )
        assertEquals(listOf(LocalDate.of(2026, 9, 1)), criteria.stayDates())
    }
}
