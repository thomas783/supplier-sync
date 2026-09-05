package com.staysync.domain.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.LocalDate

class AvailabilityPolicyTest {

    private val d1 = LocalDate.of(2026, 9, 1)
    private val d2 = LocalDate.of(2026, 9, 2)
    private val d3 = LocalDate.of(2026, 9, 3)

    @Test
    fun `전 기간 예약 가능 수는 날짜별 최소값(병목)이다`() {
        val result = AvailabilityPolicy.judge(
            stayDates = listOf(d1, d2, d3),
            remainingByDate = mapOf(d1 to 3, d2 to 1, d3 to 5),
        )
        assertEquals(Availability.Available(availableRooms = 1), result)
    }

    @Test
    fun `엄격 정책 - 숙박일 중 하나라도 재고 데이터가 누락되면 미확정이다`() {
        val result = AvailabilityPolicy.judge(
            stayDates = listOf(d1, d2, d3),
            remainingByDate = mapOf(d1 to 3, d3 to 5), // d2 누락
        )
        assertEquals(Availability.Undetermined, result)
    }

    @Test
    fun `모든 날짜 데이터가 있고 하루라도 재고가 0이면 확정 매진이다`() {
        val result = AvailabilityPolicy.judge(
            stayDates = listOf(d1, d2, d3),
            remainingByDate = mapOf(d1 to 2, d2 to 0, d3 to 4),
        )
        assertEquals(Availability.SoldOut, result)
        assertEquals(0, (result as Availability.Determined).availableRooms) // 확정 매진은 "확실한 0"
    }

    @Test
    fun `누락과 확정 매진이 겹치면 미확정이 우선한다 - 매진 단정도 거짓이므로`() {
        val result = AvailabilityPolicy.judge(
            stayDates = listOf(d1, d2, d3),
            remainingByDate = mapOf(d1 to 0, d3 to 0), // d2 누락 + 나머지 매진
        )
        assertEquals(Availability.Undetermined, result)
    }

    @Test
    fun `숙박일이 비어 있으면 미확정이다`() {
        assertEquals(
            Availability.Undetermined,
            AvailabilityPolicy.judge(emptyList(), mapOf(d1 to 5)),
        )
    }

    @Test
    fun `요청 기간 밖 날짜가 응답에 섞여 있어도 판정에 영향을 주지 않는다`() {
        val outOfRange = LocalDate.of(2026, 9, 30)
        val result = AvailabilityPolicy.judge(
            stayDates = listOf(d1, d2),
            remainingByDate = mapOf(d1 to 3, d2 to 2, outOfRange to 0), // 기간 밖 매진은 무관
        )
        assertEquals(Availability.Available(availableRooms = 2), result)
    }
}
