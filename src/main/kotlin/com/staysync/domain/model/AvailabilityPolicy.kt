package com.staysync.domain.model

import java.time.LocalDate

/**
 * 연박(N박) 예약 가능 판정 정책.
 *
 * 공급사는 날짜별 잔여 객실 수를 준다. 고객이 N박을 예약하려면 "같은 객실 타입"을 체크인일부터
 * 체크아웃 전날까지 매일 확보할 수 있어야 하므로, 전 기간 예약 가능 객실 수는 날짜별 잔여 수의
 * **최소값(병목)**이다 (예: 3박 잔여 [3, 1, 5] → 1).
 *
 * 판정은 엄격 정책이다: 공급사 응답이 요청한 숙박일 중 하나라도 누락하면 [Availability.Undetermined]로
 * 판정한다. 재고가 실제로 0인지 아닌지 모를 때 파는 것은 오버부킹 사고로 이어지므로 "확실하지 않으면
 * 팔지 않는다" — 엄격 판정의 실패는 기회 손실(우리 손해)이고 관대 판정의 실패는 오버부킹(고객 피해)이라
 * 비대칭이 명확하다. 다만 매진이라 단정하는 것도 거짓이므로, 확정 매진([Availability.SoldOut])과는
 * 구분해 돌려준다.
 */
object AvailabilityPolicy {

    /**
     * 요청 기간 전체에 대한 가용성을 판정한다.
     *
     * @param stayDates 숙박일 목록 (체크인일 ~ 체크아웃 전날)
     * @param remainingByDate 공급사가 응답한 날짜별 잔여 객실 수
     */
    fun judge(stayDates: List<LocalDate>, remainingByDate: Map<LocalDate, Int>): Availability {
        if (stayDates.isEmpty()) return Availability.Undetermined
        // 엄격: 누락일 = 미확정. 최소값은 요청 숙박일 기준으로만 구한다 — 맵에 기간 밖 날짜가 섞여도 무시
        val bookableRooms = stayDates.minOf { remainingByDate[it] ?: return Availability.Undetermined }
        return if (bookableRooms >= 1) Availability.Available(bookableRooms) else Availability.SoldOut
    }
}
