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
 * 변환 관문 (docs/QUARANTINE.md) — 결함 판정의 단일 권위. 규칙의 원천은 [DomainInvariants] 하나이고,
 * 여기는 그 술어를 중간 타입에 적용해 위반을 격리 사유([DefectReason])로 번역할 뿐이라 조립(값 객체
 * init)·엔티티와 규칙이 어긋날 수 없다. 검색·동기화 두 경로가 이 하나를 각자의 경계에서 부른다 —
 * 검색은 어댑터에서 [admit] 으로(결함 항목을 캐시·정규화 앞에서 제외), 동기화는 저장 직전
 * `PropertyMappingService` 에서 [defectOf] 로(제외하며 `skipped` 로 집계). 뒤쪽 값 객체 불변식은
 * 제거하지 않고 관문 호출 누락을 드러내는 감시자로 남긴다.
 *
 * 음수 재고는 뒤쪽에서 예외가 아니라 매진으로 조용히 흡수되던 케이스라 여기서 잡는다. 합산·병합으로
 * 사라지는 원자료는 어댑터가 함께 넘긴다 — 중복 날짜는 Map 으로 접히기 전의 [rawDates] 로, 일자별 음수
 * 요금은 합산되기 전의 [rawAmounts] 로(합계가 양수여도 일자별 음수를 잡기 위해). Spring 에 의존하지
 * 않는 순수 객체다.
 */
object ConversionGate {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 검색 경로의 관문 통과 허가 — 결함이면 warn 을 남기고 null(그 항목만 제외), 통과면 그대로 돌려준다.
     * @param rawDates Map 으로 접히기 전의 원시 날짜 목록 — 중복 날짜 결함은 여기서만 보인다
     * @param rawAmounts 합산 전의 일자별 금액(단가·세금액) — 합계가 양수여도 일자별 음수를 잡는다.
     *   일자별 금액이 없는 공급사(B: 기간 총액만)는 비운다(총액 음수는 [defectOf] 가 잡는다)
     * @param onDefect 결함 사유 콜백 — 호출부가 지표 집계 등을 붙인다(순수 객체 유지를 위한 주입점)
     */
    fun admit(
        supplier: Supplier,
        rawDates: List<LocalDate>,
        product: SupplierStayProduct,
        rawAmounts: List<Long> = emptyList(),
        onDefect: (DefectReason) -> Unit = {},
    ): SupplierStayProduct? {
        val defect = when {
            rawDates.size != rawDates.distinct().size -> DefectReason.DUPLICATE_DATE
            !rawAmounts.all { DomainInvariants.validAmount(it) } -> DefectReason.INVALID_PRICE
            else -> defectOf(product)
        } ?: return product
        onDefect(defect)
        log.warn(
            "skipping defective item: supplier={} propertyCode={} roomTypeCode={} reason={}",
            supplier, product.supplierPropertyCode, product.supplierRoomTypeCode, defect,
        )
        return null
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
