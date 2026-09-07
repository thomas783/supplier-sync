package com.staysync.sync

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.core.read.ListAppender
import com.staysync.domain.model.Supplier
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.BDDMockito.given
import org.mockito.Mockito.mock
import org.slf4j.LoggerFactory

/**
 * 배치 스케줄러의 실패 관측을 고정한다. syncAll 은 실패를 예외가 아니라 ok=false 결과로 흡수하므로,
 * 스케줄러가 결과를 검사하지 않으면 전 공급사 실패도 조용히 지나간다 — 실패 시 error 로 드러나는지 검증한다.
 */
class PropertySyncSchedulerTest {

    private val service = mock(PropertySyncService::class.java)
    private val scheduler = PropertySyncScheduler(service)

    private val logger = LoggerFactory.getLogger(PropertySyncScheduler::class.java) as Logger
    private val appender = ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>()

    @BeforeEach
    fun setUp() {
        appender.start()
        logger.addAppender(appender)
    }

    @AfterEach
    fun tearDown() {
        logger.detachAppender(appender)
    }

    private fun hasError(): Boolean = appender.list.any { it.level == Level.ERROR }

    @Test
    fun `실패한 공급사가 있으면 error 로 드러낸다`() {
        given(service.syncAll()).willReturn(
            listOf(
                SupplierSyncResult(Supplier.A, ok = true, properties = 2, roomTypes = 2),
                SupplierSyncResult(Supplier.B, ok = false, error = "sync failed"),
            ),
        )

        scheduler.scheduledSync()

        assertTrue(hasError())
    }

    @Test
    fun `전부 성공이면 error 를 남기지 않는다`() {
        given(service.syncAll()).willReturn(
            listOf(SupplierSyncResult(Supplier.A, ok = true, properties = 2, roomTypes = 2)),
        )

        scheduler.scheduledSync()

        assertFalse(hasError())
    }
}
