plugins {
  alias(libs.plugins.androidApplication) apply false
  alias(libs.plugins.androidKmpLibrary) apply false
  alias(libs.plugins.jetbrainsCompose) apply false
  alias(libs.plugins.compose.compiler) apply false
  alias(libs.plugins.kotlinMultiplatform) apply false
  alias(libs.plugins.kotlinJvm) apply false
  alias(libs.plugins.kotlinSerialization) apply false
  alias(libs.plugins.aboutlibraries.android) apply false
  alias(libs.plugins.ktfmt)
}

// Same formatter settings as the zmanim app: ktfmt, Google style, 100 columns.
//   ./gradlew ktfmtFormat   rewrites Kotlin sources and build scripts
//   ./gradlew ktfmtCheck    fails if any file is not formatted (what CI runs)
allprojects {
  apply(plugin = "com.ncorti.ktfmt.gradle")
  configure<com.ncorti.ktfmt.gradle.KtfmtExtension> {
    googleStyle()
    maxWidth.set(100)
  }
  // The script tasks scan the whole project dir; keep them out of tool and VCS state.
  tasks.withType<com.ncorti.ktfmt.gradle.tasks.KtfmtBaseTask>().configureEach {
    exclude(".jj/**", ".git/**", ".gradle/**", ".kotlin/**", ".superpowers/**", "**/build/**")
  }
}
