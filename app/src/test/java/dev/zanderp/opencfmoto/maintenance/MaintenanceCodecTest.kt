package dev.zanderp.opencfmoto.maintenance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MaintenanceCodecTest {
    @Test
    fun roundTripsBaselineOnlyState() {
        val state = MaintenanceState(baselineKm = 12_345.0, baselineSetAtMs = 1_700_000_000_000L)
        assertEquals(state, MaintenanceCodec.decode(MaintenanceCodec.encode(state)))
    }

    @Test
    fun roundTripsItemsMap() {
        val state = MaintenanceState(
            baselineKm = 100.0, baselineSetAtMs = 0L,
            items = mapOf(
                "engine_oil" to ItemState(lastServicedKm = 3100.0, lastServicedAtMs = 86_400_000L, notified = true),
                "engine_oil_filter" to ItemState(lastServicedKm = 3100.0, lastServicedAtMs = 86_400_000L),
            ),
        )
        assertEquals(state, MaintenanceCodec.decode(MaintenanceCodec.encode(state)))
    }

    @Test
    fun roundTripsNotifiedUnservicedIds() {
        val state = MaintenanceState(baselineKm = 100.0, baselineSetAtMs = 0L, notifiedUnserviced = setOf("engine_spark_plug", "frame"))
        assertEquals(state, MaintenanceCodec.decode(MaintenanceCodec.encode(state)))
    }

    @Test
    fun decodeGarbageOrIncompleteJsonReturnsNull() {
        assertNull(MaintenanceCodec.decode("not json"))
        assertNull(MaintenanceCodec.decode("{}"))
    }
}
