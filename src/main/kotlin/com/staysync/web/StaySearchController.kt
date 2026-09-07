package com.staysync.web

import com.staysync.search.StaySearchService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springdoc.core.annotations.ParameterObject
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
@Tag(name = "통합 검색", description = "여러 공급사를 병렬 조회해 표준 모델로 정규화·병합한 숙박 검색")
class StaySearchController(
    private val searchService: StaySearchService,
) {

    @Operation(
        summary = "통합 숙박 검색",
        description = "날짜·인원으로 보유 숙소 전체를 공급사들에 병렬 조회한다. 일부 공급사가 실패해도 " +
            "성공분으로 응답하고 실패 사실은 errors 에 드러난다(전 공급사 실패도 200).",
    )
    @ApiResponse(responseCode = "200", description = "검색 결과 — 확정된 상품만 노출(미확정 제외)")
    @ApiResponse(
        responseCode = "400",
        description = "검증 실패·파라미터 누락·타입 불일치 — 사람이 읽을 사유를 담은 단일 오류 포맷",
        content = [Content(schema = Schema(implementation = ApiErrorResponse::class))],
    )
    @GetMapping("/search")
    // @ParameterObject — 요청 객체의 필드들이 OpenAPI 문서에 개별 쿼리 파라미터로 펼쳐지게 한다
    fun search(@ParameterObject @Valid request: StaySearchRequest): StaySearchResponse =
        StaySearchResponse.from(searchService.search(request.toCriteria()))
}
