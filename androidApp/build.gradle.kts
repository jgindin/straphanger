import java.util.Properties

plugins {
  alias(libs.plugins.androidApplication)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.aboutlibraries.android)
  alias(libs.plugins.play.publisher)
}

/** versionCode = major * 1_000_000 + pr * 1_000, so 0.17 -> 17000. */
fun versionCodeFor(name: String): Int {
  require(Regex("""^\d+\.\d+$""").matches(name)) {
    "versionName '$name' in version.properties must look like MAJOR.PR_NUMBER"
  }
  val (major, pr) = name.split('.').map { it.toInt() }
  require(pr < 1000) { "the PR number must be below 1000 in '$name'" }
  return major * 1_000_000 + pr * 1_000
}

val appVersionName: String =
  Properties()
    .apply { rootProject.file("version.properties").inputStream().use { load(it) } }
    .getProperty("versionName") ?: error("versionName missing from version.properties")

// Upload key: keystore.properties (or -Pstraphanger.keystoreProperties=<path>), else env vars.
val keystorePropertiesFile: File =
  providers
    .gradleProperty("straphanger.keystoreProperties")
    .map { file(it) }
    .getOrElse(rootProject.file("keystore.properties"))
val releaseKeyValues: Map<String, String>? =
  when {
    keystorePropertiesFile.isFile ->
      Properties()
        .apply { keystorePropertiesFile.inputStream().use { load(it) } }
        .let { props ->
          listOf("storeFile", "storePassword", "keyAlias", "keyPassword").associateWith {
            props.getProperty(it) ?: error("$it missing from $keystorePropertiesFile")
          }
        }
    else -> {
      val env =
        mapOf(
            "storeFile" to "STRAPHANGER_UPLOAD_STORE_FILE",
            "storePassword" to "STRAPHANGER_UPLOAD_STORE_PASSWORD",
            "keyAlias" to "STRAPHANGER_UPLOAD_KEY_ALIAS",
            "keyPassword" to "STRAPHANGER_UPLOAD_KEY_PASSWORD",
          )
          .mapValues { providers.environmentVariable(it.value).orNull }
      if (env.values.all { it != null }) env.mapValues { it.value!! } else null
    }
  }
val allowDebugSignedRelease: Boolean =
  providers.gradleProperty("straphanger.allowDebugSignedRelease").orNull == "true"

android {
  namespace = "com.straphanger.app"
  compileSdk = libs.versions.android.compileSdk.get().toInt()

  defaultConfig {
    applicationId = "com.straphanger.app"
    minSdk = libs.versions.android.minSdk.get().toInt()
    targetSdk = libs.versions.android.targetSdk.get().toInt()
    versionCode = versionCodeFor(appVersionName)
    versionName = appVersionName
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    // Each test starts with empty app data (see testOptions).
    testInstrumentationRunnerArguments["clearPackageData"] = "true"
    testProguardFiles("proguard-test-apk-rules.pro")
  }
  testOptions { execution = "ANDROIDX_TEST_ORCHESTRATOR" }
  packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
  testBuildType = providers.gradleProperty("testBuildType").getOrElse("debug")
  signingConfigs {
    create("release") {
      val keys = releaseKeyValues
      if (keys != null) {
        storeFile = file(keys.getValue("storeFile"))
        storePassword = keys.getValue("storePassword")
        keyAlias = keys.getValue("keyAlias")
        keyPassword = keys.getValue("keyPassword")
      } else if (allowDebugSignedRelease) {
        logger.warn(
          "WARNING: release is signed with the DEBUG key (straphanger.allowDebugSignedRelease=true). Do not upload this build.",
        )
        initWith(getByName("debug"))
      }
    }
  }
  buildTypes {
    // Debug and releaseTest install beside the release app, so tests never touch its rides.
    getByName("debug") { applicationIdSuffix = ".debug" }
    getByName("release") {
      isMinifyEnabled = true
      isShrinkResources = true
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      signingConfig = signingConfigs["release"]
    }
    // Release R8 config plus keeps the minified test APK needs. Used only for instrumented tests
    // (-PtestBuildType=releaseTest); never upload it.
    create("releaseTest") {
      initWith(getByName("release"))
      matchingFallbacks += "release"
      applicationIdSuffix = ".releasetest"
      proguardFiles("proguard-test-rules.pro")
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
}

dependencies {
  implementation(project(":shared"))
  implementation(libs.androidx.activity.compose)
  implementation(libs.filekit.dialogs.compose)
  implementation(libs.kotlinx.io.core)
  implementation(libs.play.services.location)
  implementation(libs.aboutlibraries.compose.m3)

  debugImplementation(libs.androidx.compose.ui.tooling)
  debugImplementation(libs.androidx.compose.ui.test.manifest)

  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  androidTestImplementation(libs.androidx.test.ext.junit)
  androidTestImplementation(libs.androidx.test.espresso.core)
  androidTestImplementation(libs.androidx.test.runner)
  androidTestUtil(libs.androidx.test.orchestrator)
}

// Fail at execution time (not configuration time) so debug builds and sync keep working.
val checkReleaseSigning =
  tasks.register("checkReleaseSigning") {
    val configured = releaseKeyValues != null || allowDebugSignedRelease
    doFirst {
      if (!configured)
        throw GradleException("Release signing not configured: see docs/RELEASING.md")
    }
  }

tasks
  .matching {
    it.name in
      setOf(
        "assembleRelease",
        "bundleRelease",
        "packageRelease",
        "packageReleaseBundle",
        "assembleReleaseTest",
        "bundleReleaseTest",
        "packageReleaseTest",
        "packageReleaseTestBundle",
      )
  }
  .configureEach { dependsOn(checkReleaseSigning) }

// Listing text and graphics live in androidApp/src/main/play. Credentials are never committed;
// the release workflow points STRAPHANGER_PLAY_CREDENTIALS at a file written from a secret.
play {
  track.set("internal")
  defaultToAppBundles.set(true)
  serviceAccountCredentials.set(
    file(System.getenv("STRAPHANGER_PLAY_CREDENTIALS") ?: "play-service-account.json"),
  )
}

aboutLibraries {
  collect {
    // AboutLibraries skips any configuration whose name contains "Test", which includes the
    // releaseTest build type used for minified instrumented tests; without this its license list is
    // empty.
    // Variant filtering still limits each variant to its own classpaths, so test dependencies are
    // not added.
    includeTestVariants = true
  }
}
