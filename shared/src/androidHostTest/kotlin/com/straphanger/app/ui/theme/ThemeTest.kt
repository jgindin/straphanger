package com.straphanger.app.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.straphanger.app.testutil.contrast
import java.io.File
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The theme's colours are readable and its type never gets too small. */
@RunWith(AndroidJUnit4::class)
class ThemeTest {
  @get:Rule val rule = createComposeRule()

  private fun themed(): Pair<ColorScheme, Typography> {
    var result: Pair<ColorScheme, Typography>? = null
    rule.setContent {
      StraphangerTheme { result = MaterialTheme.colorScheme to MaterialTheme.typography }
    }
    rule.waitForIdle()
    return result!!
  }

  @Test
  fun textButtonsLinksAndSecondaryTextAreReadable() {
    val (scheme, _) = themed()
    val primary = contrast(scheme.primary, scheme.background)
    assertTrue(primary >= 4.5f, "primary on background: $primary")
    val secondary = contrast(scheme.onSurfaceVariant, scheme.surfaceContainer)
    assertTrue(secondary >= 4.5f, "onSurfaceVariant on surfaceContainer: $secondary")
    val filled = contrast(scheme.onPrimaryContainer, scheme.primaryContainer)
    assertTrue(filled >= 4.5f, "onPrimaryContainer on primaryContainer: $filled")
    val warning = contrast(MtaColors.Warning, scheme.surfaceContainer)
    assertTrue(warning >= 4.5f, "warning on surfaceContainer: $warning")
  }

  @Test
  fun bodyAndLabelTextAreNotTooSmall() {
    val (_, type) = themed()
    assertTrue(type.bodySmall.fontSize.value >= 13f, "bodySmall: ${type.bodySmall.fontSize}")
    assertTrue(type.labelSmall.fontSize.value >= 11f, "labelSmall: ${type.labelSmall.fontSize}")
  }

  private val namedColors =
    listOf("Color.White", "Color.Black", "Color.Gray", "Color.LightGray", "Color.DarkGray")

  /** Sizes and colours come from the theme; only route bullets, the map and the theme differ. */
  @Test
  fun screensUseTheThemeNotLiterals() {
    val root = File("src/commonMain/kotlin/com/straphanger/app")
    assertTrue(root.isDirectory, "run from the shared module: ${root.absolutePath}")
    val offenders =
      root
        .walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .filter { file -> file.path.contains("/ui/") || file.name == "AppChrome.kt" }
        .filterNot { it.path.contains("/ui/theme/") || it.path.contains("/ui/map/MapDrawing") }
        .flatMap { file ->
          file.readLines().mapIndexedNotNull { i, line ->
            val literal =
              line.contains("Color(0x") ||
                namedColors.any { line.contains(it) } ||
                (line.contains("fontSize =") && !line.contains("MtaRouteBullet("))
            if (literal) "${file.name}:${i + 1}: ${line.trim()}" else null
          }
        }
        .toList()
    assertTrue(offenders.isEmpty(), offenders.joinToString("\n"))
  }
}
