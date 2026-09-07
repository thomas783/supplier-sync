package com.staysync.config

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import org.springdoc.core.models.GroupedOpenApi
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * OpenAPI 문서 메타데이터 — UI 는 /swagger-ui.html, 기계용 명세는 /v3/api-docs.
 * 경로·파라미터·검증 제약은 컨트롤러 시그니처에서 자동 생성되므로 여기서는 문서의 정체와 그룹만 정한다.
 *
 * 그룹을 공개(`/api` 이하)와 운영(`/internal` 이하)으로 나눈 이유: 경로 규약(docs/API.md)이 "호환성을
 * 관리하는 공개 계약"과 "그렇지 않은 운영 도구"를 경로에서부터 구분하므로, 문서에서도 같은 구분이
 * 보여야 한다. Swagger UI 우상단 드롭다운으로 전환한다.
 */
@Configuration
class OpenApiConfig {

    @Bean
    fun openApi(): OpenAPI = OpenAPI().info(
        Info()
            .title("supplier-sync API")
            .description("여러 외부 숙박 공급사를 하나의 표준 모델로 통합한 숙박 검색 API")
            .version("v1"),
    )

    @Bean
    fun publicApi(): GroupedOpenApi = GroupedOpenApi.builder()
        .group("public")
        .pathsToMatch("/api/**")
        .build()

    @Bean
    fun internalApi(): GroupedOpenApi = GroupedOpenApi.builder()
        .group("internal")
        .pathsToMatch("/internal/**")
        .build()
}
