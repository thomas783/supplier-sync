package com.staysync.domain.model

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 정합성 규칙의 단일 원천([DomainInvariants]) 술어를 경계값으로 직접 고정한다. 값 객체·엔티티·변환
 * 관문이 모두 이 술어를 읽으므로(한 지점이 흔들리면 세 지점이 동시에 흔들린다), 경계 회귀를 여기서 잡는다.
 */
class DomainInvariantsTest {

    @Test
    fun `금액 - 0은 유효하고 음수는 무효다`() {
        assertTrue(DomainInvariants.validAmount(0))
        assertTrue(DomainInvariants.validAmount(1))
        assertFalse(DomainInvariants.validAmount(-1))
    }

    @Test
    fun `재고 - 0은 유효하다(매진), 음수는 무효다`() {
        assertTrue(DomainInvariants.validRemaining(0))
        assertFalse(DomainInvariants.validRemaining(-1))
    }

    @Test
    fun `정원 - 1 이상만 유효하다(0은 무효)`() {
        assertFalse(DomainInvariants.validOccupancy(0))
        assertTrue(DomainInvariants.validOccupancy(1))
    }

    @Test
    fun `박수 - 1 이상만 유효하다(0은 무효)`() {
        assertFalse(DomainInvariants.validNights(0))
        assertTrue(DomainInvariants.validNights(1))
    }

    @Test
    fun `통화·필수 문자열 - 공백은 무효, 비공백은 유효`() {
        assertFalse(DomainInvariants.validCurrency(" "))
        assertFalse(DomainInvariants.validCurrency("WON")) // ISO 4217 코드가 아님 (원화는 KRW)
        assertTrue(DomainInvariants.validCurrency("KRW"))
        assertFalse(DomainInvariants.validRequiredText(""))
        assertTrue(DomainInvariants.validRequiredText("A-10023"))
    }
}
