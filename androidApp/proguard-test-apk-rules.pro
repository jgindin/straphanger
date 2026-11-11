# Applied to the androidTest APK's own R8 run (testProguardFiles) only.
# errorprone annotations pulled in by the test libraries reference a JDK-only class (missing-class error).
-dontwarn javax.lang.model.element.Modifier
