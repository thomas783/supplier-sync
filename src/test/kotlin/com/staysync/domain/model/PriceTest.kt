package com.staysync.domain.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class PriceTest {

    @Test
    fun `평균 1박가는 총액 나누기 박수의 내림이다`() {
        val price = Price.of(totalAmount = 100_000, nights = 3, currency = "KRW")
        assertEquals(33_333, price.averageNightlyAmount) // 100000 / 3 = 33333.33... → 내림
    }

    @Test
    fun `0원 총액은 허용된다`() {
        val price = Price.of(totalAmount = 0, nights = 1, currency = "KRW")
        assertEquals(0, price.totalAmount)
        assertEquals(0, price.averageNightlyAmount)
    }

    @Test
    fun `음수 총액은 거부된다`() {
        assertThrows(IllegalArgumentException::class.java) {
            Price.of(totalAmount = -1, nights = 1, currency = "KRW")
        }
    }

    @Test
    fun `0박 이하는 거부된다`() {
        assertThrows(IllegalArgumentException::class.java) {
            Price.of(totalAmount = 100_000, nights = 0, currency = "KRW")
        }
    }

    @Test
    fun `빈 통화는 거부된다`() {
        assertThrows(IllegalArgumentException::class.java) {
            Price.of(totalAmount = 1_000, nights = 1, currency = " ")
        }
    }
}
