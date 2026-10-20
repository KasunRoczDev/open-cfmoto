package dev.zanderp.opencfmoto.maintenance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceHistoryTest {
    private val base = MaintenanceState(baselineKm = 1000.0, baselineSetAtMs = 0L)

    @Test
    fun markingServicedAddsAHistoryRecordWithPhotos() {
        val s = base.withServiced("engine_oil", 3100.0, 5_000L, listOf("svc-1.jpg"))
            .withServiced("engine_oil", 6000.0, 9_000L)
        val oil = s.historyFor("engine_oil")
        assertEquals(listOf(9_000L, 5_000L), oil.map { it.atMs })          // newest first
        assertEquals(listOf("svc-1.jpg"), oil.last().photos)
        assertEquals(ItemState(6000.0, 9_000L), s.items["engine_oil"])     // last service still drives due math
    }

    @Test
    fun photosCanBeAddedAndRemovedLater() {
        val s = base.withServiced("frame", 2000.0, 1_000L)
        val id = MaintenanceState.recordId("frame", 1_000L)
        val withTwo = s.withRecordPhotos(id, listOf("a.jpg", "b.jpg"))
        assertEquals(listOf("a.jpg", "b.jpg"), withTwo.historyFor("frame").single().photos)
        assertEquals(listOf("b.jpg"), withTwo.withoutRecordPhoto(id, "a.jpg").historyFor("frame").single().photos)
    }

    @Test
    fun historyRoundTripsThroughTheCodec() {
        val s = base.withServiced("engine_oil", 3100.0, 5_000L, listOf("svc-1.jpg", "svc-2.jpg"))
        assertEquals(s, MaintenanceCodec.decode(MaintenanceCodec.encode(s)))
    }

    @Test
    fun filesFromBeforeHistoryStillLoad() {
        val old = """{"baselineKm":100.0,"baselineSetAtMs":0,"items":{"engine_oil":{"lastServicedKm":3100.0,"lastServicedAtMs":5,"notified":false}},"notifiedUnserviced":[]}"""
        val s = MaintenanceCodec.decode(old)!!
        assertTrue(s.history.isEmpty())
        assertEquals(3100.0, s.items.getValue("engine_oil").lastServicedKm, 0.0)
    }
}
