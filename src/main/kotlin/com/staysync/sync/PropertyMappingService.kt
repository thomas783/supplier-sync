package com.staysync.sync

import com.staysync.domain.entity.PropertyEntity
import com.staysync.domain.entity.RoomTypeEntity
import com.staysync.domain.model.Supplier
import com.staysync.domain.repository.PropertyRepository
import com.staysync.domain.repository.RoomTypeRepository
import com.staysync.observability.SupplierMetrics
import com.staysync.supplier.ConversionGate
import com.staysync.supplier.SupplierProperty
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

/**
 * 공급사 숙소 목록을 매핑으로 반영하는 저장 담당 (멱등 upsert + 표시 속성 갱신).
 *
 * [PropertySyncService]와 별도 빈으로 두는 이유는 관심사 분리다 — 네트워크 조율(PropertySyncService)과
 * 저장(이 빈)을 나눈다. 저장은 목록을 상품 단위 청크로 쪼개 청크마다 독립 트랜잭션으로 반영한다.
 * 수천 건을 한 트랜잭션에 담으면 그 시간 내내 DB 커넥션을 쥐고 영속성 컨텍스트가 무한히 커지므로,
 * 청크 경계로 커넥션 점유 시간과 컨텍스트 크기를 유계로 만든다. 청크 경계는 선언적 @Transactional
 * 한 개로는 표현할 수 없어 [TransactionTemplate] 으로 프로그램적으로 연다 — 같은 빈 안의 호출이라도
 * 프록시 self-invocation 문제 없이 청크마다 새 트랜잭션이 열린다.
 *
 * 청크로 쪼개면 전체 원자성은 잃는다(중간 청크 실패 시 앞 청크는 커밋됨). 그러나 이 동기화는 멱등
 * upsert 이고 기동·주기·수동 3경로로 다시 돌면 수렴하므로, 부분 반영은 결함이 아니라 다음 실행이
 * 마저 채우는 자기 치유 상태다. 실패는 그대로 던져 [PropertySyncService]가 공급사 단위로 격리한다.
 */
@Service
class PropertyMappingService(
    private val propertyRepository: PropertyRepository,
    private val roomTypeRepository: RoomTypeRepository,
    private val metrics: SupplierMetrics,
    transactionManager: PlatformTransactionManager,
    // 한 트랜잭션이 잡는 상품 수의 상한 (application.yml: sync.chunk-size). 실측에 따라 조정한다.
    @Value("\${sync.chunk-size:500}") private val chunkSize: Int,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // 청크마다 독립 트랜잭션을 여는 경계라 선언적 @Transactional 한 개로는 표현할 수 없다(한 메서드 = 한
    // 트랜잭션). 청크 저장을 별도 빈으로 빼 @Transactional 을 빈 경계로 거는 대안도 있으나, 그 하나를 위해
    // 빈을 늘리지 않고 "청크 = 한 트랜잭션"을 이 자리에서 프로그램적으로 명시한다. 프로그램적 트랜잭션이라
    // 같은 빈 안의 호출이어도 프록시 self-invocation 문제가 없다.
    private val txTemplate = TransactionTemplate(transactionManager)

    /**
     * 한 공급사의 숙소 목록을 상품 단위 청크로 나눠 반영하고, 청크별 카운트를 합산해 돌려준다.
     * 각 청크는 독립 트랜잭션으로 커밋되며([persistChunk]), 한 청크가 실패하면 그 청크만 롤백된 채
     * 반영되지 못한 레코드 수를 [SyncCounts.failed] 로 집계하고 다음 청크를 계속 진행한다(best-effort).
     * 한 청크의 일시 DB 오류가 나머지 청크의 반영을 막지 않게 하려는 것이며, 실패분은 멱등 upsert 라
     * 다음 실행에서 재처리되어 수렴한다.
     */
    fun persistMappings(supplier: Supplier, properties: List<SupplierProperty>): SyncCounts {
        observeDuplicateKeys(supplier, properties)
        return properties.chunked(chunkSize)
            .fold(SyncCounts(0, 0, 0, 0)) { acc, chunk -> acc + persistChunkBestEffort(supplier, chunk) }
    }

    /**
     * 중복 자연키를 관측한다(전체 목록 기준, 청크와 무관). 자연키 유일성은 공급사의 계약이라, 목록이 같은
     * 코드를 두 번 이상 주면 계약 위반 데이터다. 저장은 last-wins 로 흡수하지만(청크 내 즉시 갱신 + 청크 간
     * 기존 행 갱신) 그건 조용한 마스킹이라, 결함 스킵과 같은 관측 원칙으로 로그·지표에 드러낸다
     * (docs/QUARANTINE.md). 객실 코드는 한 숙소 안에서만 유일하므로 숙소별로 본다.
     */
    private fun observeDuplicateKeys(supplier: Supplier, properties: List<SupplierProperty>) {
        properties.groupingBy { it.supplierPropertyCode }.eachCount()
            .filterValues { it > 1 }
            .forEach { (code, count) ->
                log.warn("duplicate property code in supplier list: supplier={} code={} count={}", supplier, code, count)
                repeat(count - 1) { metrics.recordDuplicate(supplier, "property") }
            }
        properties.forEach { property ->
            property.roomTypes.groupingBy { it.supplierRoomTypeCode }.eachCount()
                .filterValues { it > 1 }
                .forEach { (code, count) ->
                    log.warn(
                        "duplicate room type code in property: supplier={} property={} code={} count={}",
                        supplier, property.supplierPropertyCode, code, count,
                    )
                    repeat(count - 1) { metrics.recordDuplicate(supplier, "roomType") }
                }
        }
    }

    /**
     * 청크 하나를 트랜잭션으로 커밋한다. 실패하면 그 청크는 롤백되고, 반영되지 못한 레코드 수(상품 +
     * 소속 객실)를 [SyncCounts.failed] 로 환산해 계속 진행한다. Error(JVM 치명 상태)는 결과로 삼키지
     * 않고 그대로 전파한다 — [PropertySyncService.syncSupplier] 의 실패 격리와 같은 규약이다.
     */
    private fun persistChunkBestEffort(supplier: Supplier, chunk: List<SupplierProperty>): SyncCounts =
        runCatching { requireNotNull(txTemplate.execute { persistChunk(supplier, chunk) }) }
            .getOrElse { e ->
                if (e !is Exception) throw e
                val failedRecords = chunk.sumOf { 1 + it.roomTypes.size }
                log.warn(
                    "chunk persist failed, continuing: supplier={} properties={} failedRecords={} reason={}",
                    supplier, chunk.size, failedRecords, e.message,
                )
                SyncCounts(0, 0, 0, failedRecords)
            }

    /**
     * 한 청크를 하나의 트랜잭션으로 반영한다(TransactionTemplate 이 트랜잭션을 연 안에서 실행된다).
     *
     * 기존 매핑은 상품·객실 각각 벌크 조회 한 번으로 되찾아 맵으로 들고 upsert 한다 — 레코드마다
     * 조회하던 왕복(N+1)을 청크당 조회 두 번으로 줄인다. 청크 안에서 새로 저장한 상품은 기존 객실이
     * 없으므로 그 객실은 모두 insert 로 흐른다.
     *
     * 계약 밖 데이터(빈 이름, 0 이하 정원)는 **그 레코드만 보수적으로 건너뛴다** — 깨진 표시가 고객에게
     * 노출되는 것(고객 피해)보다 그 숙소가 검색에서 빠지는 것(기회 손실)이 낫다는, 가용성 판정과 같은
     * 비대칭이다. 결함 판정은 [ConversionGate] — 곧 [com.staysync.domain.model.DomainInvariants] 단일
     * 원천 — 에 위임한다(검색 경로의 관문과 같은 규칙, docs/QUARANTINE.md). 기존 매핑이 있는 레코드는
     * 갱신만 건너뛰어 멀쩡한 기존 값을 지킨다. 건너뛴 수는 결과의 `skipped` 와 경고 로그로 드러난다 —
     * 조용한 유실이 아니라 관측 가능한 스킵이다.
     */
    private fun persistChunk(supplier: Supplier, chunk: List<SupplierProperty>): SyncCounts {
        // upsert 판정용 인덱스 — 가변으로 두어 청크 안에서 새로 저장한 건도 곧바로 반영한다. 같은 자연키가
        // 한 청크에 두 번 오면(공급사 데이터 중복) save 직후 맵에 넣어 둘째 건이 첫 건을 갱신(last-wins)하게
        // 한다. 그렇지 않으면 둘째 건이 신규로 오인돼 UNIQUE 위반으로 청크가 통째로 실패한다.
        val existingProperties = propertyRepository
            .findAllBySupplierAndSupplierPropertyCodeIn(supplier, chunk.map { it.supplierPropertyCode })
            .associateByTo(HashMap()) { it.supplierPropertyCode }
        // 기존 상품의 객실만 벌크 로드 — 프록시 초기화 없이 부모 id 를 읽어 (property_id, 객실코드)로 색인한다.
        val existingPropertyIds = existingProperties.values.map { it.id }
        val existingRoomTypes: MutableMap<Pair<Long, String>, RoomTypeEntity> =
            if (existingPropertyIds.isEmpty()) {
                HashMap()
            } else {
                roomTypeRepository.findAllByProperty_IdIn(existingPropertyIds)
                    .associateByTo(HashMap()) { it.property.id to it.supplierRoomTypeCode }
            }

        var propertyCount = 0
        var roomTypeCount = 0
        var skippedCount = 0
        chunk.forEach { property ->
            // 깨진 레코드는 예외 상황이 아니라 예상 케이스 — 저장 직전 관문(DomainInvariants)으로 판정한다
            ConversionGate.defectOf(property)?.let { reason ->
                log.warn(
                    "skipping invalid property: supplier={} code={} reason={}",
                    supplier, property.supplierPropertyCode, reason,
                )
                metrics.recordQuarantined(supplier, reason, "sync")
                skippedCount += 1 + property.roomTypes.size
                return@forEach
            }
            val propertyEntity = existingProperties[property.supplierPropertyCode]
                ?.apply { updateFrom(propertyName = property.propertyName) }
                ?: propertyRepository.save(
                    PropertyEntity(supplier, property.supplierPropertyCode, property.propertyName),
                ).also { existingProperties[property.supplierPropertyCode] = it }
            propertyCount++
            property.roomTypes.forEach { roomType ->
                ConversionGate.defectOf(roomType)?.let { reason ->
                    log.warn(
                        "skipping invalid room type: supplier={} property={} code={} reason={}",
                        supplier, property.supplierPropertyCode, roomType.supplierRoomTypeCode, reason,
                    )
                    metrics.recordQuarantined(supplier, reason, "sync")
                    skippedCount++
                    return@forEach
                }
                val key = propertyEntity.id to roomType.supplierRoomTypeCode
                existingRoomTypes[key]
                    ?.apply { updateFrom(roomTypeName = roomType.roomTypeName, maxOccupancy = roomType.maxOccupancy) }
                    ?: roomTypeRepository.save(
                        RoomTypeEntity(
                            propertyEntity, roomType.supplierRoomTypeCode, roomType.roomTypeName, roomType.maxOccupancy,
                        ),
                    ).also { existingRoomTypes[key] = it }
                roomTypeCount++
            }
        }
        return SyncCounts(propertyCount, roomTypeCount, skippedCount, failed = 0)
    }

    data class SyncCounts(val properties: Int, val roomTypes: Int, val skipped: Int, val failed: Int) {
        operator fun plus(other: SyncCounts): SyncCounts = SyncCounts(
            properties + other.properties,
            roomTypes + other.roomTypes,
            skipped + other.skipped,
            failed + other.failed,
        )
    }
}
