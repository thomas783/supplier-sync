package com.staysync.sync

import com.staysync.domain.entity.PropertyEntity
import com.staysync.domain.entity.RoomTypeEntity
import com.staysync.domain.model.Supplier
import com.staysync.domain.repository.PropertyRepository
import com.staysync.domain.repository.RoomTypeRepository
import com.staysync.supplier.SupplierProperty
import com.staysync.supplier.SupplierRoomType
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
) {

    /** 한 공급사의 숙소 목록을 하나의 트랜잭션으로 반영한다. */
    @Transactional(readOnly = false)
    fun persistMappings(supplier: Supplier, properties: List<SupplierProperty>): SyncCounts {
        var propertyCount = 0
        var roomTypeCount = 0
        properties.forEach { property ->
            val propertyEntity = upsertProperty(supplier, property)
            propertyCount++
            property.roomTypes.forEach { roomType ->
                upsertRoomType(propertyEntity, roomType)
                roomTypeCount++
            }
        }
        return SyncCounts(propertyCount, roomTypeCount)
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

    data class SyncCounts(val properties: Int, val roomTypes: Int)
}
