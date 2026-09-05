package com.staysync.domain.repository

import com.staysync.domain.entity.RoomTypeEntity
import org.springframework.data.jpa.repository.JpaRepository

interface RoomTypeRepository : JpaRepository<RoomTypeEntity, Long> {

    /** 재조회 시 기존 내부 id 를 찾기 위한 자연키 조회 (매핑 생성 upsert 에 사용). */
    fun findByProperty_IdAndSupplierRoomTypeCode(
        propertyId: Long,
        supplierRoomTypeCode: String,
    ): RoomTypeEntity?

    /** 여러 숙소의 객실 타입을 한 번에 로드 (검색 응답 정규화 시 코드 → 내부 id 역방향 변환). */
    fun findAllByProperty_IdIn(propertyIds: Collection<Long>): List<RoomTypeEntity>
}
