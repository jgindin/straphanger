package com.straphanger.app.data.gtfs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class ServicePeriodTest {
    private val engine = SubwayGraphEngine()

    private fun period(iso: String) =
        engine.inferServicePeriod(Instant.parse(iso).toEpochMilliseconds())

    @Test
    fun lateNightAppliesAcrossDstFallBack() {
        // 2026-11-01 01:30 local happens twice: once in EDT, once in EST.
        assertEquals(ServicePeriod.LATE_NIGHT, period("2026-11-01T05:30:00Z"))
        assertEquals(ServicePeriod.LATE_NIGHT, period("2026-11-01T06:30:00Z"))
    }

    @Test
    fun lateNightStartsAt2345Local() {
        // Wednesday 2026-11-11, EST (UTC-5)
        assertEquals(ServicePeriod.DAYTIME, period("2026-11-12T04:44:00Z"))
        assertEquals(ServicePeriod.LATE_NIGHT, period("2026-11-12T04:45:00Z"))
    }

    @Test
    fun saturdayNoonIsWeekend() {
        assertEquals(ServicePeriod.WEEKEND, period("2026-11-14T17:00:00Z"))
    }
}
