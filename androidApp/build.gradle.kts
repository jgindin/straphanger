import java.util.Properties

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.compose.compiler)
}

/** versionCode = major * 1_000_000 + minor * 1_000 + patch, so 0.1.0 -> 1000. */
fun versionCodeFor(name: String): Int {
    require(Regex("""^\d+\.\d+\.\d+$""").matches(name)) {
        "versionName '$name' in version.properties must look like MAJOR.MINOR.PATCH"
    }
    val (major, minor, patch) = name.split('.').map { it.toInt() }
    require(minor < 1000 && patch < 1000) { "minor and patch must be below 1000 in '$name'" }
    return major * 1_000_000 + minor * 1_000 + patch
}

val appVersionName: String = Properties().apply {
    rootProject.file("version.properties").inputStream().use { load(it) }
}.getProperty("versionName") ?: error("versionName missing from version.properties")

// Upload key: keystore.properties (or -Pstraphanger.keystoreProperties=<path>), else env vars.
val keystorePropertiesFile: File = providers.gradleProperty("straphanger.keystoreProperties")
    .map { file(it) }
    .getOrElse(rootProject.file("keystore.properties"))
val releaseKeyValues: Map<String, String>? = when {
    keystorePropertiesFile.isFile -> Properties().apply {
        keystorePropertiesFile.inputStream().use { load(it) }
    }.let { props ->
        listOf("storeFile", "storePassword", "keyAlias", "keyPassword").associateWith {
            props.getProperty(it) ?: error("$it missing from $keystorePropertiesFile")
        }
    }
    else -> {
        val env = mapOf(
            "storeFile" to "STRAPHANGER_UPLOAD_STORE_FILE",
            "storePassword" to "STRAPHANGER_UPLOAD_STORE_PASSWORD",
            "keyAlias" to "STRAPHANGER_UPLOAD_KEY_ALIAS",
            "keyPassword" to "STRAPHANGER_UPLOAD_KEY_PASSWORD",
        ).mapValues { providers.environmentVariable(it.value).orNull }
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
        testProguardFiles("proguard-test-apk-rules.pro")
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
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
                logger.warn("WARNING: release is signed with the DEBUG key (straphanger.allowDebugSignedRelease=true). Do not upload this build.")
                initWith(getByName("debug"))
            }
        }
    }
    buildTypes {
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

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
}

// Fail at execution time (not configuration time) so debug builds and sync keep working.
val checkReleaseSigning = tasks.register("checkReleaseSigning") {
    val configured = releaseKeyValues != null || allowDebugSignedRelease
    doFirst {
        if (!configured) throw GradleException("Release signing not configured: see docs/RELEASING.md")
    }
}
tasks.matching {
    it.name in setOf("assembleRelease", "bundleRelease", "packageRelease", "packageReleaseBundle", "assembleReleaseTest", "packageReleaseTest")
}.configureEach { dependsOn(checkReleaseSigning) }
