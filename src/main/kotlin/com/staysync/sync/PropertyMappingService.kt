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
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 공급사 숙소 목록을 매핑으로 반영하는 저장 담당 (멱등 upsert + 표시 속성 갱신).
 *
 * [PropertySyncService]와 별도 빈으로 두는 이유는 트랜잭션 경계다 — 네트워크 호출(조율)과 저장(트랜잭션)을
 * 클래스로 나누면 @Transactional 이 빈 경계를 넘는 호출에 자연스럽게 적용되어, self-injection 같은
 * 프록시 우회가 필요 없다.
 */
@Service
class PropertyMappingService(
    private val propertyRepository: PropertyRepository,
    private val roomTypeRepository: RoomTypeRepository,
    private val metrics: SupplierMetrics,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 한 공급사의 숙소 목록을 하나의 트랜잭션으로 반영한다.
     *
     * 계약 밖 데이터(빈 이름, 0 이하 정원)는 **그 레코드만 보수적으로 건너뛴다** — 깨진 표시가 고객에게
     * 노출되는 것(고객 피해)보다 그 숙소가 검색에서 빠지는 것(기회 손실)이 낫다는, 가용성 판정과 같은
     * 비대칭이다. 결함 판정은 [ConversionGate] — 곧 [com.staysync.domain.model.DomainInvariants] 단일
     * 원천 — 에 위임한다(검색 경로의 관문과 같은 규칙, docs/QUARANTINE.md). 기존 매핑이 있는 레코드는
     * 갱신만 건너뛰어 멀쩡한 기존 값을 지킨다. 건너뛴 수는 결과의 `skipped` 와 경고 로그로 드러난다 —
     * 조용한 유실이 아니라 관측 가능한 스킵이다.
     */
    @Transactional(readOnly = false)
    fun persistMappings(supplier: Supplier, properties: List<SupplierProperty>): SyncCounts {
        var propertyCount = 0
        var roomTypeCount = 0
        var skippedCount = 0
        properties.forEach { property ->
            // 깨진 레코드는 예외 상황이 아니라 예상 케이스 — 저장 직전 관문(DomainInvariants)으로 판정한다
            ConversionGate.defectOf(property)?.let { reason ->
                log.warn(
                    "skipping invalid property: supplier={} code={} reason={}",
                    supplier, property.supplierPropertyCode, reason,
                )
                metrics.recordQuarantined(supplier, reason)
                skippedCount += 1 + property.roomTypes.size
                return@forEach
            }
            val propertyEntity = upsertProperty(supplier, property)
            propertyCount++
            property.roomTypes.forEach { roomType ->
                ConversionGate.defectOf(roomType)?.let { reason ->
                    log.warn(
                        "skipping invalid room type: supplier={} property={} code={} reason={}",
                        supplier, property.supplierPropertyCode, roomType.supplierRoomTypeCode, reason,
                    )
                    metrics.recordQuarantined(supplier, reason)
                    skippedCount++
                    return@forEach
                }
                upsertRoomType(propertyEntity, roomType)
                roomTypeCount++
            }
        }
        return SyncCounts(propertyCount, roomTypeCount, skippedCount)
    }

    // 있으면 표시 속성 갱신(더티 체킹으로 저장), 없으면 최초 insert — 자연키 UNIQUE 가 "같은 상품 = 같은 내부 id"를 보장한다

    private fun upsertProperty(supplier: Supplier, property: SupplierProperty): PropertyEntity =
        propertyRepository.findBySupplierAndSupplierPropertyCode(supplier, property.supplierPropertyCode)
            ?.apply { updateFrom(propertyName = property.propertyName) }
            ?: propertyRepository.save(PropertyEntity(supplier, property.supplierPropertyCode, property.propertyName))

    private fun upsertRoomType(property: PropertyEntity, roomType: SupplierRoomType) {
        roomTypeRepository.findByProperty_IdAndSupplierRoomTypeCode(property.id, roomType.supplierRoomTypeCode)
            ?.apply { updateFrom(roomTypeName = roomType.roomTypeName, maxOccupancy = roomType.maxOccupancy) }
            ?: roomTypeRepository.save(
                RoomTypeEntity(property, roomType.supplierRoomTypeCode, roomType.roomTypeName, roomType.maxOccupancy),
            )
    }

    data class SyncCounts(val properties: Int, val roomTypes: Int, val skipped: Int)
}
