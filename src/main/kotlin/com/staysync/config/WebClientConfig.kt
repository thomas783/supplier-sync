package com.staysync.config

import io.netty.channel.ChannelOption
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.web.reactive.function.client.WebClient
import reactor.netty.http.client.HttpClient
import java.time.Duration

/**
 * 공급사별 WebClient 구성.
 *
 * 각 공급사는 base URL·인증 키가 다르므로 별도 WebClient 를 만들어 자격 있는 빈으로 등록한다.
 * 연결·응답 타임아웃을 Reactor Netty 레벨에서 걸어 모든 호출에 일괄 적용한다.
 */
@Configuration
@EnableConfigurationProperties(SupplierProperties::class)
class WebClientConfig(
    private val properties: SupplierProperties,
) {

    @Bean("supplierAWebClient")
    fun supplierAWebClient(): WebClient = build(properties.a)

    @Bean("supplierBWebClient")
    fun supplierBWebClient(): WebClient = build(properties.b)

    private fun build(endpoint: SupplierProperties.Endpoint): WebClient {
        val httpClient = HttpClient.create()
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, properties.connectTimeoutMs)
            .responseTimeout(Duration.ofMillis(properties.responseTimeoutMs))
        return WebClient.builder()
            .baseUrl(endpoint.baseUrl)
            .defaultHeader("X-Api-Key", endpoint.apiKey)
            .clientConnector(ReactorClientHttpConnector(httpClient))
            .build()
    }
}
