package com.staysync.config

import io.netty.channel.ChannelOption
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.web.reactive.function.client.WebClient
import reactor.netty.http.client.HttpClient
import reactor.netty.resources.ConnectionProvider
import java.time.Duration

/**
 * 공급사별 WebClient 구성.
 *
 * 각 공급사는 base URL·인증 키가 다르므로 별도 WebClient 를 만들어 자격 있는 빈으로 등록한다.
 * 연결·응답 타임아웃을 Reactor Netty 레벨에서 건다. 클라이언트 기본 응답 타임아웃은 짧은 쪽(검색용)이다
 * — 관대한 값이 필요한 동기화 경로가 어댑터에서 요청 단위로 덮어쓴다 (덮어쓰기를 잊어도 짧게 끊기는
 * 쪽이 안전한 기본값이다).
 *
 * 커넥션 풀은 **공급사별로 분리**한다 — 공급사별 동시성(bulkhead)을 격리하는 것과 같은 원칙이라, 한
 * 공급사의 커넥션 소진이 다른 공급사로 번지지 않는다. 풀 상한은 bulkhead 동시성보다 크게 잡아(부등식
 * `bulkhead 동시성 < maxConnections`) 전송 계층이 숨은 병목이 되지 않게 하고, pending-acquire 대기는
 * 응답 타임아웃보다 짧게 잡아 풀 고갈이 늦은 타임아웃으로 위장되지 않게 한다 (docs/INTEGRATION.md).
 */
@Configuration
@EnableConfigurationProperties(SupplierProperties::class)
class WebClientConfig(
    private val properties: SupplierProperties,
) {

    @Bean("supplierAWebClient")
    fun supplierAWebClient(): WebClient = build("supplier-a", properties.a)

    @Bean("supplierBWebClient")
    fun supplierBWebClient(): WebClient = build("supplier-b", properties.b)

    private fun build(poolName: String, endpoint: SupplierProperties.Endpoint): WebClient {
        val connectionProvider = ConnectionProvider.builder(poolName)
            .maxConnections(properties.maxConnections)
            .pendingAcquireTimeout(Duration.ofMillis(properties.pendingAcquireTimeoutMs))
            .build()
        val httpClient = HttpClient.create(connectionProvider)
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, properties.connectTimeoutMs)
            .responseTimeout(Duration.ofMillis(properties.searchResponseTimeoutMs))
        return WebClient.builder()
            .baseUrl(endpoint.baseUrl)
            .defaultHeader("X-Api-Key", endpoint.apiKey)
            .clientConnector(ReactorClientHttpConnector(httpClient))
            .build()
    }
}
