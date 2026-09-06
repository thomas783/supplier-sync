package com.staysync.search

import com.staysync.domain.model.StayProduct
import com.staysync.domain.model.Supplier
import java.time.LocalDate

/**
 * 통합 검색 입력. 값 검증(날짜 순서·인원 하한)은 컨트롤러가 계약(docs/API.md)에 따라 이미 마친 뒤라
 * 여기서 반복하지 않는다.
 */
data class StaySearchCriteria(
    val checkIn: LocalDate,
    val checkOut: LocalDate,
    val adults: Int,
    val children: Int,
) {
    /** 숙박일 목록 (체크인일 ~ 체크아웃 전날). 체크아웃일은 숙박일에 포함되지 않는다. */
    fun stayDates(): List<LocalDate> =
        generateSequence(checkIn) { it.plusDays(1) }
            .takeWhile { it.isBefore(checkOut) }
            .toList()
}

/**
 * 통합 검색 결과.
 *
 * @property stays 정규화·병합을 마친 표준 숙박 상품. 미확정([com.staysync.domain.model.Availability.Undetermined])도
 *   포함한다 — 응답에서의 제외는 노출 정책이므로 웹 계층의 몫이다.
 * @property errors 조회에 실패한 공급사와 사유. 비어 있으면 전체 성공.
 */
data class StaySearchResult(
    val stays: List<StayProduct>,
    val errors: List<SupplierError>,
)

data class SupplierError(
    val supplier: Supplier,
    val reason: String,
)
