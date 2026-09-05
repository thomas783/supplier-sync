package com.staysync.sync

import com.staysync.domain.model.Supplier
import com.staysync.supplier.SupplierCallException
import com.staysync.supplier.SupplierClient
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 공급사 숙소 목록 → 내부 매핑 동기화 (docs/ARCHITECTURE.md 의 동기화 유스케이스).
 *
 * 숙소 목록은 자주 바뀌지 않고(정적 콘텐츠), 재고·요금은 매번 바뀐다. 이 성격 차이 때문에 숙소 목록은
 * 매 검색마다 부르지 않고 미리 매핑으로 저장해 두었다가 재사용한다. 동기화 시점은 기동 시 1회 +
 * 주기 배치 + 수동 트리거 3경로다.
 *
 * 갱신 정책:
 * - 표시 속성(숙소명·객실명·정원)이 바뀌면 도메인 메서드(updateFrom)로 반영한다.
 * - 공급사 목록에서 사라진 숙소는 처리하지 않고 그대로 둔다 — 스펙에 삭제 신호가 없어 일시 누락과 영구
 *   제거를 구분할 수 없고, 검색은 공급사 실시간 응답 기반이라 사라진 매핑은 결과에 나오지 않으며,
 *   지웠다가 돌아오면 내부 id 가 바뀌어 "같은 상품은 언제나 같은 id" 보장이 흔들린다.
 *
 * 동시성·트랜잭션:
 * - 3경로가 겹쳐 돌면 upsert 의 "조회 → 없으면 저장"이 경합해 UNIQUE 위반이 날 수 있다. 단일 인스턴스
 *   에서는 [syncLock] 으로 syncAll 을 직렬화해 방지한다. (다중 인스턴스 확장 시엔 분산 락이 필요하다.)
 * - 네트워크 호출(fetchProperties)은 트랜잭션 밖에서 수행하고, 저장은 [PropertyMappingService]의
 *   트랜잭션 경계 안에서 수행한다 — DB 커넥션을 원격 I/O 동안 잡고 있지 않는다.
 *
 * 실패 격리: 한 공급사의 숙소 목록 조회가 실패해도 다른 공급사 동기화는 계속되고, 결과 요약에 공급사별
 * 성공/실패가 드러난다.
 */
@Service
class PropertySyncService(
    private val clients: List<SupplierClient>,
    private val mappingService: PropertyMappingService,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val syncLock = ReentrantLock()

    /** 모든 공급사 동기화. 공급사별 결과 요약을 반환한다. */
    fun syncAll(): List<SupplierSyncResult> = syncLock.withLock {
        clients.map { syncSupplier(it) }
    }

    private fun syncSupplier(client: SupplierClient): SupplierSyncResult =
        try {
            val properties = client.fetchProperties() // 네트워크 호출 — 트랜잭션 밖
            val counts = mappingService.persistMappings(client.supplier, properties)
            log.info(
                "property sync ok: supplier={} properties={} roomTypes={}",
                client.supplier, counts.properties, counts.roomTypes,
            )
            SupplierSyncResult(client.supplier, ok = true, properties = counts.properties, roomTypes = counts.roomTypes)
        } catch (e: SupplierCallException) {
            log.warn("property sync failed: supplier={} reason={}", client.supplier, e.reason)
            SupplierSyncResult(client.supplier, ok = false, error = e.reason)
        }
}

/** 공급사별 동기화 결과 요약 — 수동 트리거 응답에도 그대로 쓰인다 (docs/API.md). */
data class SupplierSyncResult(
    val supplier: Supplier,
    val ok: Boolean,
    val properties: Int = 0,
    val roomTypes: Int = 0,
    val error: String? = null,
)
