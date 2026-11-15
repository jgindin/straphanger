import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  alias(libs.plugins.kotlinJvm)
  alias(libs.plugins.kotlinSerialization)
  application
}

kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }

java {
  sourceCompatibility = JavaVersion.VERSION_17
  targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
  implementation(libs.kotlinx.serialization.json)
  testImplementation(libs.kotlin.test)
}

application { mainClass.set("com.straphanger.tools.basemap.MainKt") }

tasks.test { useJUnitPlatform() }

// `./gradlew :tools:gtfs-import:run` resolves relative paths against the repository root.
tasks.named<JavaExec>("run") { workingDir = rootProject.projectDir }
