package com.straphanger.app.data.capture

import com.straphanger.app.data.local.InMemoryPersistenceBackend
import com.straphanger.app.data.local.RidePersistenceBackend
import com.straphanger.app.testutil.TestNetwork
import com.straphanger.app.ui.SAMPLE_TIMELINE_JSON
import com.straphanger.app.ui.StraphangerViewModel
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest

class TimelineImportThreadingTest {
  /**
   * Runs work on Dispatchers.Default and records whether the calling thread is inside one of its
   * blocks. Per thread: other compute work may run on another thread at the same moment.
   */
  private class MarkingDispatcher : CoroutineDispatcher() {
    private val onThisThread = ThreadLocal.withInitial { false }
    val running: Boolean
      get() = onThisThread.get()

    override fun dispatch(context: CoroutineContext, block: Runnable) {
      Dispatchers.Default.dispatch(
        context,
        Runnable {
          onThisThread.set(true)
          try {
            block.run()
          } finally {
            onThisThread.set(false)
          }
        },
      )
    }
  }

  @Test
  fun ridesAreSavedOutsideTheComputeDispatcher() = runTest {
    val compute = MarkingDispatcher()
    val savedWhileComputing = mutableListOf<Boolean>()
    val inner = InMemoryPersistenceBackend()
    val backend =
      object : RidePersistenceBackend {
        override fun loadSnapshotJson() = inner.loadSnapshotJson()

        override fun saveSnapshotJson(json: String) {
          savedWhileComputing.add(compute.running)
          inner.saveSnapshotJson(json)
        }
      }
    val vm =
      StraphangerViewModel(
        backend = backend,
        dispatcher = Dispatchers.Unconfined,
        computeDispatcher = compute,
        networkLoader = { TestNetwork.data },
      )

    val summary = vm.importTimelineFile(SAMPLE_TIMELINE_JSON.encodeToByteArray())

    assertEquals(2, summary.ridesImported)
    assertTrue(savedWhileComputing.isNotEmpty())
    assertTrue(savedWhileComputing.none { it })
  }

}
