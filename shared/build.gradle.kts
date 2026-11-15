import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  alias(libs.plugins.kotlinMultiplatform)
  alias(libs.plugins.androidKmpLibrary)
  alias(libs.plugins.jetbrainsCompose)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.kotlinSerialization)
}

kotlin {
  android {
    namespace = "com.straphanger.shared"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    minSdk = libs.versions.android.minSdk.get().toInt()

    // Required so Compose resources (composeResources/files/...) are packaged.
    androidResources.enable = true

    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }

    // commonTest runs on the JVM host via :shared:testAndroidHostTest. Android resources are
    // included so the Robolectric Compose tests in androidHostTest can start an activity.
    withHostTest { isIncludeAndroidResources = true }
    withDeviceTest {}
  }

  listOf(
      iosArm64(),
      iosSimulatorArm64(),
    )
    .forEach { iosTarget ->
      iosTarget.binaries.framework {
        baseName = "ComposeApp"
        isStatic = true
      }
    }

  sourceSets {
    androidMain.dependencies {
      implementation(compose.preview)
      implementation(libs.ktor.client.okhttp)
    }
    commonMain.dependencies {
      implementation(compose.runtime)
      implementation(compose.foundation)
      implementation(compose.material3)
      implementation(compose.ui)
      implementation(compose.components.resources)
      implementation(compose.components.uiToolingPreview)
      implementation(libs.androidx.lifecycle.viewmodel.compose)
      implementation(libs.androidx.lifecycle.runtime.compose)
      implementation(libs.kotlinx.coroutines.core)
      implementation(libs.kotlinx.serialization.json)
      implementation(libs.kotlinx.datetime)
      implementation(libs.kotlinx.io.core)
      implementation(libs.ktor.client.core)
      implementation(libs.filekit.dialogs.compose)
      implementation(libs.compose.ui.backhandler)
    }
    iosMain.dependencies { implementation(libs.ktor.client.darwin) }
    commonTest.dependencies {
      implementation(libs.kotlin.test)
      implementation(libs.kotlinx.coroutines.test)
      implementation(libs.ktor.client.mock)
    }
    // Robolectric Compose UI tests: the real screens, rendered on the JVM.
    getByName("androidHostTest").dependencies {
      implementation(libs.robolectric)
      implementation(libs.androidx.test.core)
      implementation(libs.androidx.test.ext.junit)
      implementation(libs.androidx.compose.ui.test.junit4)
      implementation(libs.androidx.compose.ui.test.manifest)
    }
  }
}
