# Applied only when the androidTest APK is minified (testBuildType=release).
# errorprone annotations pulled in by the Espresso/test libraries reference a JDK-only class.
-dontwarn javax.lang.model.element.Modifier

# The test runner (AndroidJUnitRunner) calls androidx.tracing.Trace, which R8 removes from the
# app because the app never calls it. Applied to the app's R8 run only when -PtestBuildType=release.
-keep class androidx.tracing.** { *; }

# The test APK is minified separately and shares kotlin-stdlib classes with the app, so the app's
# copy must keep the exact names and members the test APK's copy expects.
-keep class kotlin.** { *; }
-keep class kotlinx.coroutines.** { *; }
# Compose test hooks (InfiniteAnimationPolicy, test dispatchers, semantics tree access) live in the app's
# Compose runtime and are only reached from the test APK.
-keep class androidx.compose.** { *; }
-keep class androidx.collection.** { *; }
-keep class com.google.common.util.concurrent.** { *; }
-keep class androidx.concurrent.** { *; }
-keep class androidx.lifecycle.** { *; }
-keep class androidx.savedstate.** { *; }
-keep class androidx.activity.** { *; }
