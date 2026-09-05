package com.staysync.domain.entity

import jakarta.persistence.Column
import jakarta.persistence.EntityListeners
import jakarta.persistence.MappedSuperclass
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.LastModifiedDate
import org.springframework.data.jpa.domain.support.AuditingEntityListener
import java.time.LocalDateTime

/**
 * 모든 엔티티가 상속하는 감사(audit) 베이스.
 *
 * 생성·수정 시각은 **Spring Data JPA Auditing 이 관리**한다 — 스키마 관리 주체를 Hibernate(ddl-auto)로
 * 정하면서 감사 관리 주체도 애플리케이션 한쪽으로 통일했다(docs/ERD.md). 매핑은 배치로 갱신되는 정적
 * 데이터라 "언제 마지막으로 동기화됐는지"를 운영에서 추적하기 위해 둔다.
 *
 * 타입은 datetime — 시간대 변환 없이 쓴 값이 그대로 저장되므로, 애플리케이션·DB·JDBC 세 계층의
 * KST 고정이 전제다(docs/ERD.md). 정밀도는 초 단위로 충분해 columnDefinition 으로 소수점 없는
 * datetime 을 고정한다.
 */
@MappedSuperclass
@EntityListeners(AuditingEntityListener::class)
abstract class BaseTimeEntity {

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false, columnDefinition = "datetime")
    var createdAt: LocalDateTime? = null
        protected set

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false, columnDefinition = "datetime")
    var updatedAt: LocalDateTime? = null
        protected set
}
