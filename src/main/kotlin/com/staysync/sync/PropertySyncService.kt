package com.staysync.sync

import com.staysync.domain.model.Supplier
import com.staysync.resilience.SupplierResilience
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
    private val resilience: SupplierResilience,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val syncLock = ReentrantLock()

    /** 모든 공급사 동기화. 공급사별 결과 요약을 반환한다. */
    fun syncAll(): List<SupplierSyncResult> = syncLock.withLock {
        clients.map { syncSupplier(it) }
    }

    // 실패를 던지지 않고 값(결과 요약)으로 변환해 들고 다니는 자리 — runCatching 이 맞는 패턴이다.
    // 저장 단계의 어떤 실패(DB 오류 등)도 해당 공급사의 실패로 격리되어 다음 공급사 진행과
    // "항상 리스트 응답" 계약이 유지된다. 단 Error 계열(JVM 치명 상태)은 결과로 포장하지 않는다.
    private fun syncSupplier(client: SupplierClient): SupplierSyncResult =
        runCatching {
            // 네트워크 호출 — 트랜잭션 밖. 일시 실패는 재시도로 흡수한다 (배치 성격이라 검색보다 시도가 많다)
            val properties = resilience.decorateSyncRetry(client.supplier) { client.fetchProperties() }
            mappingService.persistMappings(client.supplier, properties)
        }.fold(
            onSuccess = { counts ->
                log.info(
                    "property sync ok: supplier={} properties={} roomTypes={} skipped={}",
                    client.supplier, counts.properties, counts.roomTypes, counts.skipped,
                )
                SupplierSyncResult(
                    client.supplier, ok = true,
                    properties = counts.properties, roomTypes = counts.roomTypes, skipped = counts.skipped,
                )
            },
            onFailure = { e ->
                if (e !is Exception) throw e
                val reason = if (e is SupplierCallException) e.reason else "sync failed: ${e.message}"
                log.warn("property sync failed: supplier={} reason={}", client.supplier, reason)
                SupplierSyncResult(client.supplier, ok = false, error = reason)
            },
        )
}

/** 공급사별 동기화 결과 요약 — 수동 트리거 응답에도 그대로 쓰인다 (docs/API.md). */
data class SupplierSyncResult(
    val supplier: Supplier,
    val ok: Boolean,
    val properties: Int = 0,
    val roomTypes: Int = 0,
    val skipped: Int = 0,
    val error: String? = null,
)
