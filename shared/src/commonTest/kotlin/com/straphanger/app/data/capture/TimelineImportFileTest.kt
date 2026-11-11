package com.straphanger.app.data.capture

import com.straphanger.app.data.local.InMemoryPersistenceBackend
import com.straphanger.app.testutil.TestNetwork
import com.straphanger.app.ui.SAMPLE_TIMELINE_JSON
import com.straphanger.app.ui.StraphangerViewModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest

class TimelineImportFileTest {

    private fun newViewModel() = StraphangerViewModel(
        backend = InMemoryPersistenceBackend(),
        dispatcher = Dispatchers.Unconfined,
        computeDispatcher = Dispatchers.Unconfined,
        networkLoader = { TestNetwork.data }
    )

    @Test
    fun importingSameFileTwiceAddsNothing() = runTest {
        val vm = newViewModel()
        val bytes = SAMPLE_TIMELINE_JSON.encodeToByteArray()

        val first = vm.importTimelineFile(bytes)
        assertEquals(2, first.ridesImported)
        val ridesAfterFirst = vm.dao.getAllRides().size

        val second = vm.importTimelineFile(bytes)
        assertEquals(0, second.ridesImported)
        assertEquals(first.ridesImported, second.duplicatesSkipped)
        assertEquals(ridesAfterFirst, vm.dao.getAllRides().size)
        assertEquals(second.copy(importedRecords = emptyList()), vm.uiState.value.lastImportSummary)
        assertFalse(vm.uiState.value.timelineImportInProgress)
    }

    @Test
    fun fileThatIsNotATimelineImportsNothing() = runTest {
        val vm = newViewModel()
        val summary = vm.importTimelineFile("not json at all".encodeToByteArray())
        assertEquals(0, summary.rawSegmentsFound)
        assertEquals(0, summary.ridesImported)
        assertNotNull(vm.uiState.value.lastImportSummary)
        assertTrue(vm.dao.getAllRides().isEmpty())
    }

    @Test
    fun largeTimelineImportsWithinBudget() = runTest(timeout = 60.seconds) {
        val vm = newViewModel()
        // 86 St (626) and 14 St-Union Sq (635), at the stations' own coordinates.
        val origin = "40.779492°, -73.955589°"
        val destination = "40.734673°, -73.989951°"
        val baseMs = 1_700_000_000_000L
        val hourMs = 3_600_000L
        val json = buildString {
            append("{\"semanticSegments\":[")
            for (i in 0 until 20_000) {
                if (i > 0) append(',')
                val type = if (i % 2 == 0) "IN_SUBWAY" else "WALKING"
                append(
                    "{\"startTime\":\"${iso(baseMs + i * hourMs)}\",\"endTime\":\"${iso(baseMs + i * hourMs + 20 * 60_000L)}\"," +
                        "\"activity\":{\"start\":{\"latLng\":\"$origin\"},\"end\":{\"latLng\":\"$destination\"}," +
                        "\"topCandidate\":{\"type\":\"$type\"}}}"
                )
            }
            append("]}")
        }

        val summary = vm.importTimelineFile(json.encodeToByteArray())

        assertEquals(10_000, summary.ridesImported)
        assertEquals(10_000, vm.dao.getAllRides().size)
    }

    private fun iso(epochMs: Long): String =
        kotlin.time.Instant.fromEpochMilliseconds(epochMs).toString()
}
