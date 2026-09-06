package com.staysync.web

import com.staysync.search.StaySearchService
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 통합 검색 API (계약은 docs/API.md).
 *
 * GET /api/v1/stays/search?checkIn=2026-09-01&checkOut=2026-09-04&adults=2&children=0
 * 검색 조건은 날짜와 인원뿐(지역·키워드 필터는 비범위)이고, 대상은 보유 숙소 전체다.
 * 요청 검증은 [StaySearchRequest] 의 선언이 담당하고, 위반의 400 변환은 [GlobalExceptionHandler] 몫이다.
 */
@RestController
@RequestMapping("/api/v1/stays")
class StaySearchController(
    private val searchService: StaySearchService,
) {

    @GetMapping("/search")
    fun search(@Valid request: StaySearchRequest): StaySearchResponse =
        StaySearchResponse.from(searchService.search(request.toCriteria()))
}
