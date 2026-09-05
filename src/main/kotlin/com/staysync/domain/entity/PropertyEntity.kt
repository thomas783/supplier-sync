package com.staysync.domain.entity

import com.staysync.domain.model.Supplier
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.PrePersist
import jakarta.persistence.PreUpdate
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint

/**
 * 내부 숙소 레코드 — 공급사 숙소 코드 ↔ 내부 식별자 매핑.
 *
 * 재고·요금은 호출마다 바뀌므로 저장하지 않는다. 저장하는 것은 자주 바뀌지 않는
 * "숙소 목록"에서 온 정보뿐이다 — 공급사 숙소 코드, 그 코드에 부여한 내부 대리키, 숙소명.
 *
 * 이 매핑은 편의가 아니라 조회의 전제다: 공급사 재고·요금 API 는 지역 검색 없이 숙소 코드 목록을
 * 받으므로, 검색 시 이 테이블에서 공급사별 코드를 꺼내 조회한다.
 *
 * 자연키는 (supplier, supplierPropertyCode). 이 조합의 UNIQUE 제약이 "같은 공급사 상품은 언제
 * 조회해도 같은 내부 id"를 DB 수준에서 보장한다. 매핑 생성은 자연키 조회 후 없으면 insert 하는
 * 멱등 upsert 다.
 *
 * 모든 필드는 protected set — 외부에서 setter 로 임의 변경할 수 없고, 변경이 필요해지는 시점에
 * 의도가 명시된 도메인 메서드로만 연다.
 */
@Entity
@Table(
    name = "property",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_property_supplier_code",
            columnNames = ["supplier", "supplier_property_code"],
        ),
    ],
)
class PropertyEntity(
    supplier: Supplier,
    supplierPropertyCode: String,
    propertyName: String,
) : BaseTimeEntity() {

    /** 내부 숙소 식별자. 공급사 코드를 노출하지 않기 위한 자동 증가 대리키. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0
        protected set

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    var supplier: Supplier = supplier
        protected set

    @Column(name = "supplier_property_code", nullable = false)
    var supplierPropertyCode: String = supplierPropertyCode
        protected set

    @Column(name = "property_name", nullable = false)
    var propertyName: String = propertyName
        protected set

    // 상태 불변식의 유일한 정의 — 생성(init)·갱신(updateFrom)·저장 직전(JPA 콜백) 세 관문이 모두 호출한다.
    // 아는 경로에서는 즉시 터지고, updateFrom 을 우회하는 미래의 변경 경로도 flush 직전 그물망에는 걸린다.
    // Hibernate 의 조회 시 인스턴스화(no-arg 생성자)는 init 을 거치지 않으므로 하이드레이션에는 영향이 없다.
    @PrePersist
    @PreUpdate
    fun validate() {
        require(supplierPropertyCode.isNotBlank()) { "supplierPropertyCode must not be blank" }
        require(propertyName.isNotBlank()) { "propertyName must not be blank" }
    }

    init {
        validate()
    }

    /** 재동기화에서 표시 속성을 갱신한다 — 자연키(supplier, supplierPropertyCode)는 정체성이라 갱신 대상이 아니다. */
    fun updateFrom(propertyName: String) {
        this.propertyName = propertyName
        validate()
    }
}
