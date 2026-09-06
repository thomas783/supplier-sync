package com.staysync.web

import com.staysync.search.StaySearchCriteria
import com.staysync.search.StaySearchService
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate

/**
 * 통합 검색 API (계약은 docs/API.md).
 *
 * GET /api/v1/stays/search?checkIn=2026-09-01&checkOut=2026-09-04&adults=2&children=0
 * 검색 조건은 날짜와 인원뿐(지역·키워드 필터는 비범위)이고, 대상은 보유 숙소 전체다.
 */
@RestController
@RequestMapping("/api/v1/stays")
class StaySearchController(
    private val searchService: StaySearchService,
) {

    @GetMapping("/search")
    fun search(
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) checkIn: LocalDate,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) checkOut: LocalDate,
        @RequestParam adults: Int,
        @RequestParam(defaultValue = "0") children: Int,
    ): StaySearchResponse {
        validate(checkIn, checkOut, adults, children)
        val result = searchService.search(StaySearchCriteria(checkIn, checkOut, adults, children))
        return StaySearchResponse.from(result)
    }

    // 400 규칙과 메시지는 계약(docs/API.md)의 일부 — 위반 시 핸들러가 일관된 에러 응답으로 변환한다
    private fun validate(checkIn: LocalDate, checkOut: LocalDate, adults: Int, children: Int) {
        if (!checkOut.isAfter(checkIn)) {
            // 체크아웃일은 숙박일에 포함되지 않으므로 같은 날은 0박이라 성립 불가
            throw BadRequestException("checkOut은 checkIn보다 뒤여야 합니다")
        }
        if (adults < 1) {
            // 성인 미동반 숙박은 법령·업계 관행상 불가하다는 도메인 규칙
            throw BadRequestException("adults는 1 이상이어야 합니다")
        }
        if (children < 0) {
            throw BadRequestException("children은 0 이상이어야 합니다")
        }
    }
}
