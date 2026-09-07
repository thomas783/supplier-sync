package com.staysync.supplier

import com.staysync.domain.model.DomainInvariants
import com.staysync.domain.model.Supplier
import org.slf4j.LoggerFactory
import java.time.LocalDate

/** 변환 관문의 결함 사유 (docs/QUARANTINE.md). 격리 기록 도입 시 레코드의 사유 코드가 된다. */
enum class DefectReason {
    DUPLICATE_DATE,
    INVALID_PRICE,
    MISSING_FIELD,
    INVALID_INVENTORY,
    INVALID_OCCUPANCY,
}

/**
 * 변환 관문 (docs/QUARANTINE.md) — 판정(defectOf)과 처분(admit)을 함께 가진다.
 *
 * 규칙의 원천은 [DomainInvariants] 하나다 — 여기는 그 술어를 중간 타입에 적용해 위반을 격리
 * 사유([DefectReason])로 번역할 뿐이라, 조립(값 객체 init)·엔티티와 규칙이 어긋날 수 없다. 처분도
 * 중간 타입 위에서 돌므로 공급사와 무관하다 — 어댑터는 필드 매핑만 맡고, 결함 항목의 warn·제외
 * 안무는 [admit] 이 공통으로 수행한다. 뒤쪽 불변식은 제거하지 않고 관문 호출 누락을 드러내는 감시자로
 * 남긴다. 음수 재고는 뒤쪽에서 예외가 아니라 매진으로 조용히 흡수되던 케이스라, 결함이 은폐되지 않도록
 * 여기서 잡는다.
 *
 * 중복 날짜는 Map 으로 접히기 전의 원시 날짜 목록에서만 보이므로(associate 가 조용히 병합해 버린다),
 * 어댑터가 rawDates 를 함께 넘기고 관문이 검사한다.
 * Spring 에 의존하지 않는 순수 객체로 두 어댑터가 공유한다.
 */
object ConversionGate {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 관문 통과 허가 — 결함이면 warn 을 남기고 null(그 항목만 제외), 통과면 그대로 돌려준다.
     * @param rawDates Map 으로 접히기 전의 원시 날짜 목록 — 중복 날짜 결함은 여기서만 보인다
     */
    fun admit(supplier: Supplier, rawDates: List<LocalDate>, product: SupplierStayProduct): SupplierStayProduct? {
        val defect = when {
            rawDates.size != rawDates.distinct().size -> DefectReason.DUPLICATE_DATE
            else -> defectOf(product)
        } ?: return product
        log.warn(
            "skipping defective item: supplier={} propertyCode={} roomTypeCode={} reason={}",
            supplier, product.supplierPropertyCode, product.supplierRoomTypeCode, defect,
        )
        return null
    }

    /** 결함 룸타입은 걸러내고, 숙소 자체가 결함이면 숙소째 제외한다. */
    fun admit(supplier: Supplier, property: SupplierProperty): SupplierProperty? {
        defectOf(property)?.let { defect ->
            log.warn(
                "skipping defective property: supplier={} propertyCode={} reason={}",
                supplier, property.supplierPropertyCode, defect,
            )
            return null
        }
        val intactRoomTypes = property.roomTypes.filter { roomType ->
            val defect = defectOf(roomType) ?: return@filter true
            log.warn(
                "skipping defective room type: supplier={} propertyCode={} roomTypeCode={} reason={}",
                supplier, property.supplierPropertyCode, roomType.supplierRoomTypeCode, defect,
            )
            false
        }
        return property.copy(roomTypes = intactRoomTypes)
    }

    fun defectOf(product: SupplierStayProduct): DefectReason? = when {
        !DomainInvariants.validAmount(product.grossTotalAmount) -> DefectReason.INVALID_PRICE
        !DomainInvariants.validCurrency(product.currency) -> DefectReason.MISSING_FIELD
        !product.remainingByDate.values.all { DomainInvariants.validRemaining(it) } -> DefectReason.INVALID_INVENTORY
        else -> null
    }

    fun defectOf(property: SupplierProperty): DefectReason? = when {
        !DomainInvariants.validRequiredText(property.supplierPropertyCode) ||
            !DomainInvariants.validRequiredText(property.propertyName) -> DefectReason.MISSING_FIELD
        else -> null
    }

    fun defectOf(roomType: SupplierRoomType): DefectReason? = when {
        !DomainInvariants.validRequiredText(roomType.supplierRoomTypeCode) ||
            !DomainInvariants.validRequiredText(roomType.roomTypeName) -> DefectReason.MISSING_FIELD
        !DomainInvariants.validOccupancy(roomType.maxOccupancy) -> DefectReason.INVALID_OCCUPANCY
        else -> null
    }
}
