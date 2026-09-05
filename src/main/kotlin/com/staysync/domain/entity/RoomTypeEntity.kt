package com.staysync.domain.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.ForeignKey
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.PrePersist
import jakarta.persistence.PreUpdate
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint

/**
 * 내부 객실 타입 레코드 — 공급사 객실 타입 코드 ↔ 내부 식별자 매핑.
 *
 * 객실 타입 코드는 "해당 숙소 안에서만" 유일하므로 다른 숙소에 같은 코드가 존재할 수 있다.
 * 객실 타입 하나를 유일하게 가리키는 자연키는 (공급사, 숙소 코드, 객실 타입 코드)이고, 여기서는
 * 이미 식별된 [PropertyEntity]를 부모로 참조해 (property_id, 객실 코드)로 축약한다 — 선행 컬럼이
 * property_id 라서 숙소별 객실 타입 조회도 이 UNIQUE 인덱스로 커버된다.
 *
 * 부모는 LAZY 연관으로 참조하고 명시적 FK 제약(fk_room_type_property)으로 참조 무결성을 보장한다.
 * 조회는 부모의 id 만 읽으므로(프록시 초기화 없이) 연관 그래프 로딩이나 N+1 이 발생하지 않는다.
 *
 * 모든 필드는 protected set — 외부에서 setter 로 임의 변경할 수 없고, 변경이 필요해지는 시점에
 * 의도가 명시된 도메인 메서드로만 연다.
 */
@Entity
@Table(
    name = "room_type",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_room_type_property_code",
            columnNames = ["property_id", "supplier_room_type_code"],
        ),
    ],
)
class RoomTypeEntity(
    property: PropertyEntity,
    supplierRoomTypeCode: String,
    roomTypeName: String,
    maxOccupancy: Int,
) : BaseTimeEntity() {

    /** 내부 객실 타입 식별자. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0
        protected set

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
        name = "property_id",
        nullable = false,
        updatable = false,
        foreignKey = ForeignKey(name = "fk_room_type_property"),
    )
    var property: PropertyEntity = property
        protected set

    @Column(name = "supplier_room_type_code", nullable = false)
    var supplierRoomTypeCode: String = supplierRoomTypeCode
        protected set

    @Column(name = "room_type_name", nullable = false)
    var roomTypeName: String = roomTypeName
        protected set

    @Column(name = "max_occupancy", nullable = false)
    var maxOccupancy: Int = maxOccupancy
        protected set

    // 상태 불변식의 단일 정의이자 영속 최후 방어선 — 계약 밖 데이터의 1차 필터는 동기화의 경계
    // 검증(Bean Validation)이 저장 전에 끝내므로, 여기서 터진다는 것은 버그이고 트랜잭션이 죽는 게 맞다.
    // 생성(init)·갱신(updateFrom)·저장 직전(JPA 콜백 — 우회 경로의 그물망)이 모두 이 함수를 지난다.
    // Hibernate 의 조회 시 인스턴스화(no-arg 생성자)는 init 을 거치지 않으므로 하이드레이션에는 영향이 없다.
    @PrePersist
    @PreUpdate
    fun validate() {
        require(supplierRoomTypeCode.isNotBlank()) { "supplierRoomTypeCode must not be blank" }
        require(roomTypeName.isNotBlank()) { "roomTypeName must not be blank" }
        require(maxOccupancy > 0) { "maxOccupancy must be positive: $maxOccupancy" }
    }

    init {
        validate()
    }

    /** 재동기화에서 표시 속성을 갱신한다 — 자연키(property, supplierRoomTypeCode)는 정체성이라 갱신 대상이 아니다. */
    fun updateFrom(roomTypeName: String, maxOccupancy: Int) {
        this.roomTypeName = roomTypeName
        this.maxOccupancy = maxOccupancy
        validate()
    }
}
