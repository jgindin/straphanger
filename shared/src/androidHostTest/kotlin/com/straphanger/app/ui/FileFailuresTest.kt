package com.straphanger.app.ui

import io.github.vinceglb.filekit.exceptions.FileKitException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException

class FileFailuresTest {

  @Test
  fun fileFailuresAreReturned() = runTest {
    val failures =
      listOf(
        SecurityException("grant revoked"),
        IOException("gone"),
        FileKitException("no stream"),
        IllegalStateException("no date"),
      )
    for (failure in failures) {
      assertEquals(failure, catchingFileFailure { throw failure }.exceptionOrNull())
    }
  }

  @Test
  fun cancellationAndOtherFailuresAreThrown() = runTest {
    assertFailsWith<CancellationException> {
      catchingFileFailure { throw CancellationException("left") }
    }
    assertFailsWith<IllegalArgumentException> {
      catchingFileFailure { throw IllegalArgumentException("bug") }
    }
    assertFailsWith<CancellationException> {
      catchingSaveFailure { throw CancellationException("left") }
    }
  }

  @Test
  fun saveFailuresAreReturned() = runTest {
    assertEquals("disk full", catchingSaveFailure { throw IOException("disk full") }.reason())
    assertEquals("blocked", catchingSaveFailure { throw IllegalStateException("blocked") }.reason())
  }

  private fun Result<Unit>.reason(): String? = exceptionOrNull()?.message
}
