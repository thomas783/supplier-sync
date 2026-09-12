package com.staysync.domain.model

import java.math.BigDecimal
import java.util.Currency

/**
 * 도메인 정합성 규칙의 단일 원천.
 *
 * 같은 규칙이 세 지점에서 강제된다 — ① 원시 응답 → 중간 타입 변환 관문(ConversionGate, 결함 격리),
 * ② 중간 타입 → 표준 모델 조립(값 객체 init), ③ 엔티티 생성·저장(영속 최후 방어선). 규칙 술어를
 * 여기 한 번만 정의하고 세 지점이 모두 이것을 읽게 하여, 규칙 변경이 한 곳에서 끝나고 지점 간
 * 어긋남(관문 구멍)을 구조적으로 막는다 (docs/QUARANTINE.md "관문의 완전성").
 *
 * 술어(참/거짓)만 둔다 — 위반 시의 처분(격리·예외·트랜잭션 중단)은 각 지점의 소관이라 여기 담지
 * 않는다. Spring 도 JPA 도 모르는 순수 객체다.
 */
object DomainInvariants {

    /** 금액 — 통화 최소 단위 정수(KRW), 음수 불가. */
    fun validAmount(amount: Long): Boolean = amount >= 0

    /** 원 통화 금액 — BigDecimal, 음수 불가(0 허용). */
    fun validAmount(amount: BigDecimal): Boolean = amount.signum() >= 0

    /** 통화 코드 — 유효한 ISO 4217 코드여야 한다(java.util.Currency 로 파싱 가능). 공백·미지정 코드는 무효. */
    fun validCurrency(currency: String): Boolean = runCatching { Currency.getInstance(currency) }.isSuccess

    /** 날짜별 잔여 객실 수 — 음수 불가. 0 은 매진이지 결함이 아니다. */
    fun validRemaining(remaining: Int): Boolean = remaining >= 0

    /** 정원 — 1명 이상. */
    fun validOccupancy(maxOccupancy: Int): Boolean = maxOccupancy > 0

    /** 박수 — 1박 이상 (체크아웃 > 체크인). */
    fun validNights(nights: Int): Boolean = nights > 0

    /** 필수 문자열(공급사 식별 코드·표시 이름) — 공백 불가. */
    fun validRequiredText(value: String): Boolean = value.isNotBlank()
}
