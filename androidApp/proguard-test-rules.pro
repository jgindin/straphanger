# Used ONLY by the releaseTest build type (instrumented tests against the minified app).
# The androidTest APK is minified separately and shares these libraries with the app, so the app copy
# must keep the class names/members the test APK was compiled against. Each rule below was added after
# the quoted crash; none apply to the shipping release build.

# NoClassDefFoundError: androidx/tracing/Trace (AndroidJUnitRunner.onCreate)
-keep class androidx.tracing.** { *; }
# NoSuchMethodError: kotlin lazy/stdlib constructor (androidx.test TestDirCalculator)
-keep class kotlin.** { *; }
# NoClassDefFoundError: kotlinx/coroutines/JobKt
-keep class kotlinx.coroutines.** { *; }
# NoClassDefFoundError: androidx/compose/ui/platform/InfiniteAnimationPolicy (compose test rule)
-keep class androidx.compose.** { *; }
# NoSuchMethodError: androidx.collection mutableIntObjectMapOf (called from compose test code)
-keep class androidx.collection.** { *; }
# NoClassDefFoundError: com/google/common/util/concurrent/ListenableFuture
-keep class com.google.common.util.concurrent.** { *; }
-keep class androidx.concurrent.** { *; }
# NoClassDefFoundError: androidx/lifecycle/ViewTreeLifecycleOwner (and its savedstate/activity owners)
-keep class androidx.lifecycle.** { *; }
-keep class androidx.savedstate.** { *; }
-keep class androidx.activity.** { *; }
