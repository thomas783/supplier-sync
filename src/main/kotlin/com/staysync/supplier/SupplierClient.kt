package com.staysync.supplier

import com.staysync.domain.model.Supplier
import reactor.core.publisher.Mono

/**
 * 공급사 연동 포트.
 *
 * 검색·동기화 계층은 이 인터페이스에만 의존하고, 구체 공급사(A/B) 어댑터는 구현으로 주입된다.
 * 신규 공급사 추가 = 이 인터페이스를 구현하는 어댑터 하나를 추가하는 것 (기존 계층 수정 없음).
 * 어댑터는 내부 id 를 모른다 — 공급사 코드 기반의 중간 표준 타입까지만 변환한다 (docs/ARCHITECTURE.md).
 */
interface SupplierClient {

    val supplier: Supplier

    /**
     * 숙소 목록 조회 (정적 콘텐츠). 매핑 동기화에 사용.
     * 블로킹 호출 — 배치 성격의 동기화에서만 쓰이므로 병렬성이 중요하지 않다.
     * 실패 시 [SupplierCallException].
     */
    fun fetchProperties(): List<SupplierProperty>

    /**
     * 재고·요금 조회 (숙소 코드 목록 단위, 한 호출당 최대 50개).
     * Mono 로 반환해 검색 계층이 여러 공급사·여러 청크를 논블로킹으로 병렬 호출한다.
     * 실패는 Mono 의 onError 로 [SupplierCallException] 을 전달한다 (검색 계층이 부분 실패로 흡수).
     */
    fun fetchStayProducts(query: StayProductQuery): Mono<List<SupplierStayProduct>>
}
