package com.straphanger.app.widget

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.provideContent
import androidx.glance.text.Text
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

/** Redrawing through Glance, for ids the system may no longer know. */
@RunWith(AndroidJUnit4::class)
class GlanceWidgetRendererTest {
  private class EmptyWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) = provideContent {
      Text("")
    }
  }

  @Test
  fun updatingAnUnknownWidgetDoesNotThrowAndReportsItGone() = runTest {
    val gone = mutableListOf<Int>()
    val renderer =
      GlanceWidgetRenderer(ApplicationProvider.getApplicationContext(), EmptyWidget()) {
        gone += it
      }
    val redraws = GlanceWidgetRenderer.redraws.value

    renderer.update(42)

    assertEquals(listOf(42), gone)
    assertEquals(redraws, GlanceWidgetRenderer.redraws.value)
  }
}
