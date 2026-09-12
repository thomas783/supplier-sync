package com.staysync.domain.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.util.Currency

class PriceTest {

    private val krw = Currency.getInstance("KRW")
    private val rate1 = 1.toBigDecimal() // KRW 공급사: 환율 1 (no-op)

    @Test
    fun `평균 1박가는 총액 나누기 박수의 내림이다`() {
        val price = Price.of(originalTotal = 100_000.toBigDecimal(), currency = krw, exchangeRate = rate1, nights = 3)
        assertEquals(33_333.toBigDecimal(), price.averageNightly.converted.amount) // 100000 / 3 → 내림
    }

    @Test
    fun `0원 총액은 허용된다`() {
        val price = Price.of(originalTotal = 0.toBigDecimal(), currency = krw, exchangeRate = rate1, nights = 1)
        assertEquals(0.toBigDecimal(), price.total.converted.amount)
        assertEquals(0.toBigDecimal(), price.averageNightly.converted.amount)
    }

    @Test
    fun `음수 총액은 거부된다`() {
        assertThrows(IllegalArgumentException::class.java) {
            Price.of(originalTotal = (-1).toBigDecimal(), currency = krw, exchangeRate = rate1, nights = 1)
        }
    }

    @Test
    fun `0박 이하는 거부된다`() {
        assertThrows(IllegalArgumentException::class.java) {
            Price.of(originalTotal = 100_000.toBigDecimal(), currency = krw, exchangeRate = rate1, nights = 0)
        }
    }

    @Test
    fun `환율을 적용해 원가를 KRW 로 환산하고 원가·통화를 보존한다`() {
        // 외화 100 × 환율 1350 = 135,000 KRW(내림). 환산가는 KRW, 원가는 원 통화로 각자 통화를 단다.
        val usd = Currency.getInstance("USD")
        val price = Price.of(originalTotal = 100.toBigDecimal(), currency = usd, exchangeRate = 1350.toBigDecimal(), nights = 1)
        assertEquals(135_000.toBigDecimal(), price.total.converted.amount)
        assertEquals(krw, price.total.converted.currency)
        assertEquals(100.toBigDecimal(), price.total.original.amount)
        assertEquals(usd, price.total.original.currency)
    }
}
