package com.staysync.domain.repository

import com.staysync.domain.entity.PropertyEntity
import com.staysync.domain.model.Supplier
import org.springframework.data.jpa.repository.JpaRepository

interface PropertyRepository : JpaRepository<PropertyEntity, Long> {

    /** 재조회 시 기존 내부 id 를 찾기 위한 자연키 조회 (매핑 생성 upsert 에 사용). */
    fun findBySupplierAndSupplierPropertyCode(
        supplier: Supplier,
        supplierPropertyCode: String,
    ): PropertyEntity?

    /** 검색 시 공급사별 대상 숙소 코드를 꺼내기 위한 조회. */
    fun findAllBySupplier(supplier: Supplier): List<PropertyEntity>
}
