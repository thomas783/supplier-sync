package com.staysync.domain.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.LocalDate

class PriceTest {

    @Test
    fun `평균 1박가는 총액 나누기 박수의 내림이다`() {
        val price = Price.of(totalAmount = 100_000, nights = 3, nightlyRates = emptyList(), currency = "KRW")
        assertEquals(33_333, price.averageNightlyAmount) // 100000 / 3 = 33333.33... → 내림
    }

    @Test
    fun `실측을 주는 공급사는 일자별 금액이 보존된다`() {
        val rates = listOf(
            NightlyRate(LocalDate.of(2026, 9, 1), 50_000),
            NightlyRate(LocalDate.of(2026, 9, 2), 70_000),
        )
        val price = Price.of(totalAmount = 120_000, nights = 2, nightlyRates = rates, currency = "KRW")
        assertEquals(rates, price.nightlyRates)
        assertEquals(60_000, price.averageNightlyAmount)
    }

    @Test
    fun `실측이 없는 공급사는 일자별 금액이 빈 리스트다`() {
        val price = Price.of(totalAmount = 429_000, nights = 3, nightlyRates = emptyList(), currency = "KRW")
        assertEquals(emptyList<NightlyRate>(), price.nightlyRates)
    }

    @Test
    fun `0원 총액은 허용된다`() {
        val price = Price.of(totalAmount = 0, nights = 1, nightlyRates = emptyList(), currency = "KRW")
        assertEquals(0, price.totalAmount)
        assertEquals(0, price.averageNightlyAmount)
    }

    @Test
    fun `음수 총액은 거부된다`() {
        assertThrows(IllegalArgumentException::class.java) {
            Price.of(totalAmount = -1, nights = 1, nightlyRates = emptyList(), currency = "KRW")
        }
    }

    @Test
    fun `0박 이하는 거부된다`() {
        assertThrows(IllegalArgumentException::class.java) {
            Price.of(totalAmount = 100_000, nights = 0, nightlyRates = emptyList(), currency = "KRW")
        }
    }

    @Test
    fun `빈 통화는 거부된다`() {
        assertThrows(IllegalArgumentException::class.java) {
            Price.of(totalAmount = 1_000, nights = 1, nightlyRates = emptyList(), currency = " ")
        }
    }
}
