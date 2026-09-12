package com.staysync.sync

import com.staysync.domain.entity.PropertyEntity
import com.staysync.domain.entity.RoomTypeEntity
import com.staysync.domain.model.Supplier
import com.staysync.domain.repository.PropertyRepository
import com.staysync.domain.repository.RoomTypeRepository
import com.staysync.observability.SupplierMetrics
import com.staysync.supplier.ConversionGate
import com.staysync.supplier.SupplierProperty
import com.staysync.supplier.SupplierRoomType
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
    // 청크 트랜잭션의 DB 작업 상한(초, application.yml: sync.chunk-tx-timeout-seconds).
    @Value("\${sync.chunk-tx-timeout-seconds:30}") private val chunkTxTimeoutSeconds: Int,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // 청크마다 독립 트랜잭션을 여는 경계라 선언적 @Transactional 한 개로는 표현할 수 없다(한 메서드 = 한
    // 트랜잭션). 청크 저장을 별도 빈으로 빼 @Transactional 을 빈 경계로 거는 대안도 있으나, 그 하나를 위해
    // 빈을 늘리지 않고 "청크 = 한 트랜잭션"을 이 자리에서 프로그램적으로 명시한다. 프로그램적 트랜잭션이라
    // 같은 빈 안의 호출이어도 프록시 self-invocation 문제가 없다.
    private val txTemplate = TransactionTemplate(transactionManager).apply {
        // 네트워크 조회는 트랜잭션 밖(PropertySyncService)이라 이 타임아웃은 순수 DB 작업에만 걸린다 —
        // DB 스톨(락 경합·느린 쿼리)이 청크를 무한정 붙잡아 스레드를 고갈시키는 것을 막는 상한.
        timeout = chunkTxTimeoutSeconds
    }

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
            .fold(SyncCounts()) { acc, chunk -> acc + persistChunkBestEffort(supplier, chunk) }
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
                // 저장 레코드는 숙소·객실 두 종류다 — 1 은 숙소 레코드 자신, roomTypes.size 는 그 아래 객실 건수.
                val failedRecords = chunk.sumOf { 1 + it.roomTypes.size }
                log.warn(
                    "chunk persist failed, continuing: supplier={} properties={} failedRecords={} reason={}",
                    supplier, chunk.size, failedRecords, e.message,
                )
                SyncCounts(failed = failedRecords)
            }

    /**
     * 한 청크를 하나의 트랜잭션으로 반영한다(TransactionTemplate 이 트랜잭션을 연 안에서 실행된다).
     *
     * 기존 매핑은 상품·객실 각각 벌크 조회 한 번으로 되찾아 맵으로 들고 upsert 한다 — 레코드마다
     * 조회하던 왕복(N+1)을 청크당 조회 두 번으로 줄인다. 상품별 반영 결과를 [SyncCounts] 합산으로
     * 모아 돌려준다(가변 카운터 없이 각 단계가 자기 몫의 카운트만 낸다).
     *
     * 아래 함수들로 나눠, 정책(결함 스킵·카운팅)과 기계(영속·맵 캐싱)를 층으로 분리한다:
     * ```
     * persistChunk                 오케스트레이션 — 맵 2개 로드 후 상품별 결과 fold 합산
     * ├─ loadExistingProperties       상품 자연키 벌크 조회(distinct) → 가변 맵
     * ├─ loadExistingRoomTypes        객실 벌크 조회 → (property_id, 코드) 색인
     * ├─ persistProperty              [정책] 상품 결함 스킵 → upsert → 객실 카운트 합산
     * │  └─ upsertProperty               [기계] 상품 cache-aside upsert
     * └─ persistRoomType              [정책] 객실 결함 스킵 → upsert → 카운트
     *    └─ upsertRoomType               [기계] 객실 cache-aside upsert
     * ```
     */
    private fun persistChunk(supplier: Supplier, chunk: List<SupplierProperty>): SyncCounts {
        val existingProperties = loadExistingProperties(supplier, chunk)
        val existingRoomTypes = loadExistingRoomTypes(existingProperties.values)
        return chunk.fold(SyncCounts()) { counts, property ->
            counts + persistProperty(supplier, property, existingProperties, existingRoomTypes)
        }
    }

    /**
     * 청크의 상품 자연키로 기존 매핑을 벌크 조회해 upsert 판정용 인덱스로 만든다. 가변 맵인 이유는 청크
     * 안에서 새로 저장한 건도 곧바로 반영하기 위해서다 — 같은 자연키가 한 청크에 두 번 오면(공급사 데이터
     * 중복) save 직후 맵에 넣어 둘째 건이 첫 건을 갱신(last-wins)하게 한다. 그렇지 않으면 둘째 건이 신규로
     * 오인돼 UNIQUE 위반으로 청크가 통째로 실패한다.
     */
    private fun loadExistingProperties(
        supplier: Supplier,
        chunk: List<SupplierProperty>,
    ): MutableMap<String, PropertyEntity> =
        propertyRepository
            // 청크에 같은 코드가 중복될 수 있어(공급사 데이터 중복) distinct 로 IN 목록의 바인드 중복을 없앤다.
            .findAllBySupplierAndSupplierPropertyCodeIn(supplier, chunk.map { it.supplierPropertyCode }.distinct())
            .associateByTo(HashMap()) { it.supplierPropertyCode }

    /**
     * 기존 상품의 객실만 벌크 로드해 (property_id, 객실코드)로 색인한다 — 프록시 초기화 없이 부모 id 만
     * 읽는다. 객실 코드는 숙소 안에서만 유일하므로 부모 id 를 키에 포함한다. 신규 상품은 기존 객실이 없어
     * 여기 실리지 않고, 그 객실은 모두 insert 로 흐른다.
     */
    private fun loadExistingRoomTypes(
        properties: Collection<PropertyEntity>,
    ): MutableMap<Pair<Long, String>, RoomTypeEntity> {
        val propertyIds = properties.map { it.id }
        if (propertyIds.isEmpty()) return HashMap()
        return roomTypeRepository.findAllByProperty_IdIn(propertyIds)
            .associateByTo(HashMap()) { it.property.id to it.supplierRoomTypeCode }
    }

    /**
     * 상품 하나와 딸린 객실을 upsert 하고 반영 카운트를 돌려준다.
     *
     * 계약 밖 데이터(빈 이름, 0 이하 정원)는 **그 레코드만 보수적으로 건너뛴다** — 깨진 표시가 고객에게
     * 노출되는 것(고객 피해)보다 그 숙소가 검색에서 빠지는 것(기회 손실)이 낫다는, 가용성 판정과 같은
     * 비대칭이다. 결함 판정은 [ConversionGate] — 곧 [com.staysync.domain.model.DomainInvariants] 단일
     * 원천 — 에 위임한다(검색 경로의 관문과 같은 규칙, docs/QUARANTINE.md). 상품이 결함이면 딸린 객실까지
     * 통째로 스킵하고, 기존 매핑이 있으면 표시 속성만 갱신해 멀쩡한 기존 값을 지킨다. 건너뛴 수는 결과의
     * `skipped` 와 경고 로그로 드러난다 — 조용한 유실이 아니라 관측 가능한 스킵이다.
     */
    private fun persistProperty(
        supplier: Supplier,
        property: SupplierProperty,
        existingProperties: MutableMap<String, PropertyEntity>,
        existingRoomTypes: MutableMap<Pair<Long, String>, RoomTypeEntity>,
    ): SyncCounts {
        ConversionGate.defectOf(property)?.let { reason ->
            log.warn(
                "skipping invalid property: supplier={} code={} reason={}",
                supplier, property.supplierPropertyCode, reason,
            )
            metrics.recordQuarantined(supplier, reason, "sync")
            // 상품이 결함이면 그 상품(1)과 딸린 객실 전부를 건너뛴 것으로 센다.
            return SyncCounts(skipped = 1 + property.roomTypes.size)
        }
        val propertyEntity = upsertProperty(supplier, property, existingProperties)
        // 상품 1건 반영 + 객실별 카운트 합산. 결함 상품에서 조기 반환했으므로 여기 도달하면 상품은 반영됨.
        return property.roomTypes.fold(SyncCounts(properties = 1)) { counts, roomType ->
            counts + persistRoomType(supplier, property.supplierPropertyCode, propertyEntity, roomType, existingRoomTypes)
        }
    }

    /**
     * 상품 매핑을 cache-aside upsert 한다 — 기존이 있으면 표시 속성만 갱신하고, 없으면 insert 후 맵에 넣어
     * 돌려준다. insert 직후 맵에 넣는 것은 같은 청크의 중복 코드가 UNIQUE 위반 없이 last-wins 로 흡수되게
     * 하기 위해서다(그렇지 않으면 둘째 건이 신규로 오인된다).
     */
    private fun upsertProperty(
        supplier: Supplier,
        property: SupplierProperty,
        existingProperties: MutableMap<String, PropertyEntity>,
    ): PropertyEntity =
        existingProperties[property.supplierPropertyCode]
            ?.apply { updateFrom(propertyName = property.propertyName) }
            ?: propertyRepository.save(
                PropertyEntity(supplier, property.supplierPropertyCode, property.propertyName),
            ).also { existingProperties[property.supplierPropertyCode] = it }

    /**
     * 객실 하나를 upsert 하고 반영 카운트를 돌려준다. 결함(빈 이름, 0 이하 정원)이면 그 객실만 건너뛰고,
     * 기존 매핑이 있으면 표시 속성만 갱신한다. 신규는 insert 후 맵에 넣어 같은 청크의 중복 코드가 UNIQUE
     * 위반 없이 last-wins 로 흡수되게 한다.
     */
    private fun persistRoomType(
        supplier: Supplier,
        propertyCode: String,
        propertyEntity: PropertyEntity,
        roomType: SupplierRoomType,
        existingRoomTypes: MutableMap<Pair<Long, String>, RoomTypeEntity>,
    ): SyncCounts {
        ConversionGate.defectOf(roomType)?.let { reason ->
            log.warn(
                "skipping invalid room type: supplier={} property={} code={} reason={}",
                supplier, propertyCode, roomType.supplierRoomTypeCode, reason,
            )
            metrics.recordQuarantined(supplier, reason, "sync")
            return SyncCounts(skipped = 1)
        }
        upsertRoomType(propertyEntity, roomType, existingRoomTypes)
        return SyncCounts(roomTypes = 1)
    }

    /**
     * 객실 매핑을 cache-aside upsert 한다 — 키는 (property_id, 객실코드)라 다른 숙소의 같은 코드와 충돌하지
     * 않는다. 기존이 있으면 표시 속성만 갱신하고, 없으면 insert 후 맵에 넣어(같은 청크의 중복 코드 last-wins
     * 흡수) 준다.
     */
    private fun upsertRoomType(
        propertyEntity: PropertyEntity,
        roomType: SupplierRoomType,
        existingRoomTypes: MutableMap<Pair<Long, String>, RoomTypeEntity>,
    ) {
        val key = propertyEntity.id to roomType.supplierRoomTypeCode
        existingRoomTypes[key]
            ?.apply { updateFrom(roomTypeName = roomType.roomTypeName, maxOccupancy = roomType.maxOccupancy) }
            ?: roomTypeRepository.save(
                RoomTypeEntity(
                    propertyEntity, roomType.supplierRoomTypeCode, roomType.roomTypeName, roomType.maxOccupancy,
                ),
            ).also { existingRoomTypes[key] = it }
    }

    data class SyncCounts(
        val properties: Int = 0,
        val roomTypes: Int = 0,
        val skipped: Int = 0,
        val failed: Int = 0,
    ) {
        operator fun plus(other: SyncCounts): SyncCounts = SyncCounts(
            properties + other.properties,
            roomTypes + other.roomTypes,
            skipped + other.skipped,
            failed + other.failed,
        )
    }
}
