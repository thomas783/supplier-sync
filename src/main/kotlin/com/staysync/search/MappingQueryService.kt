package com.staysync.search

import com.staysync.domain.model.Property
import com.staysync.domain.model.RoomType
import com.staysync.domain.model.Supplier
import com.staysync.domain.repository.PropertyRepository
import com.staysync.domain.repository.RoomTypeRepository
import com.staysync.supplier.SupplierStayProduct
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 검색 1건이 필요로 하는 매핑을 읽어 조회 계획으로 만든다.
 *
 * 읽기는 readOnly 트랜잭션 하나로 묶어 일관되게 로드하고, 원격 공급사 호출은 이 경계 밖에서 수행한다 —
 * 트랜잭션이 원격 I/O 를 가로지르지 않는다는 규칙(CLAUDE.md)의 실현이다.
 *
 * 표준 모델([Property]/[RoomType])로의 투영도 이 트랜잭션 안에서 끝낸다. 두 모델은 "검증된 매핑
 * 엔티티의 투영"이라는 전제로 재검증하지 않으므로, 생성 지점을 엔티티를 손에 쥔 여기 하나로 유지한다.
 */
@Service
class MappingQueryService(
    private val propertyRepository: PropertyRepository,
    private val roomTypeRepository: RoomTypeRepository,
) {

    @Transactional(readOnly = true)
    fun loadPlan(supplier: Supplier): SupplierQueryPlan? {
        val properties = propertyRepository.findAllBySupplier(supplier)
        // 매핑 없음 = 이 공급사는 건너뜀. 전 공급사가 비어도 검색은 오류가 아니라 200 + 빈 결과 (docs/API.md)
        if (properties.isEmpty()) return null

        // 비영속 표준 모델(Property/RoomType)의 생성 지점은 엔티티를 쥔 이 트랜잭션 안 하나뿐이다
        val propertyByCode = properties.associate {
            it.supplierPropertyCode to Property(id = it.id, name = it.propertyName)
        }
        // 숙소 id 전부를 IN 절 한 번으로 — 숙소마다 조회하는 N+1 을 피해 이 메서드의 쿼리는 정확히 2번
        val roomTypes = roomTypeRepository.findAllByProperty_IdIn(properties.map { it.id })
        // LAZY 부모의 id 만 읽어(프록시 초기화 없이) 역방향 조회 맵을 만든다.
        // 키가 (숙소 id, 객실 코드) 쌍인 이유: 객실 코드는 공급사 전역이 아니라 숙소 안에서만 유일하다
        val roomTypeByKey = roomTypes.associate {
            (it.property.id to it.supplierRoomTypeCode) to
                RoomType(id = it.id, name = it.roomTypeName, maxOccupancy = it.maxOccupancy)
        }

        // 계획의 두 방향 — propertyCodes 는 공급사에 보낼 것(아웃바운드), lookup 은 응답을 되번역할 것(인바운드)
        return SupplierQueryPlan(
            supplier = supplier,
            propertyCodes = properties.map { it.supplierPropertyCode },
            lookup = MappingLookup(supplier, propertyByCode, roomTypeByKey),
        )
    }
}

/** 한 공급사에 대한 검색 조회 계획: 조회 대상 숙소 코드 + 코드 → 표준 모델 역방향 조회. */
data class SupplierQueryPlan(
    val supplier: Supplier,
    val propertyCodes: List<String>,
    val lookup: MappingLookup,
)

/**
 * 공급사 코드 → 표준 모델 역방향 조회 캐시.
 * 검색 1건 처리 동안 미리 로드해, 응답 처리가 상품마다 DB 를 조회하지 않게 한다.
 */
class MappingLookup(
    private val supplier: Supplier,
    private val propertyByCode: Map<String, Property>,
    private val roomTypeByKey: Map<Pair<Long, String>, RoomType>,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 응답 상품을 매핑과 대조해 표준 모델 쌍으로 치환한다. 매핑에 없는 코드(숙소 목록에 없던 상품이
     * 재고 응답에 나타난 경우)는 내부 id 를 만들 수 없으므로 null — 호출부는 그 상품만 건너뛴다.
     * 즉석 매핑 생성(읽기 경로의 쓰기)은 하지 않는다 — 동기화(수동 트리거 포함)가 매핑을 채우면
     * 다음 검색부터 자연히 나타난다.
     */
    fun resolve(product: SupplierStayProduct): Pair<Property, RoomType>? {
        val property = propertyByCode[product.supplierPropertyCode] ?: run {
            log.warn("skipping unmapped property: supplier={} code={}", supplier, product.supplierPropertyCode)
            return null
        }
        val roomType = roomTypeByKey[property.id to product.supplierRoomTypeCode] ?: run {
            log.warn(
                "skipping unmapped roomType: supplier={} propertyId={} code={}",
                supplier, property.id, product.supplierRoomTypeCode,
            )
            return null
        }
        return property to roomType
    }
}
