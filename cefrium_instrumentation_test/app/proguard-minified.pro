# App-side rules for the minified build type only.
#
# AGP leaves out of the test APK every class the app already has, so the
# instrumentation runner and Compose's test library run on the app's copies of
# androidx, Kotlin and JUnit. R8 shrinks those in the app because the app never
# calls them, and the run dies before or inside the tests (measured, one after
# another: androidx.tracing, kotlin.LazyKt, kotlinx.coroutines.JobKt,
# compose MonotonicFrameClock, androidx.collection). Keep those libraries.
#
# Not kept here, on purpose: org.chromium, com.cefrium, org.jni_zero, GMS and
# Firebase. Their survival under R8 is what this run tests, and it depends only
# on the rules the SDK ships. Reflective use of androidx by Chromium is masked
# here; smoke-minified.sh covers it with the browser app and no extra rules.
-keep class androidx.** { *; }
-keep interface androidx.** { *; }
-keep class kotlin.** { *; }
-keep class kotlinx.** { *; }
-keep class org.junit.** { *; }
-keep class junit.** { *; }
-keep class org.hamcrest.** { *; }
# Espresso needs javax.inject, which the app gets from the Google libraries
# (datatransport, firebase-annotations) and never calls.
-keep class javax.inject.** { *; }

# Tests that look inside on purpose: CefriumEngineSettingsTest reads Chromium's
# command line, which the app never does; CefriumWebPushTest swaps the GCM
# InstanceID for a fake; CefriumNoGmsTest asks the engine whether it sees the
# Google libraries.
-keep class org.chromium.base.CommandLine { public *; }
-keep class org.chromium.components.gcm_driver.instance_id.InstanceIDWithSubtype { *; }
-keep interface org.chromium.components.gcm_driver.instance_id.InstanceIDWithSubtype$FakeFactory { *; }
-keep class org.chromium.gms.ChromiumPlayServicesAvailability { public static boolean isGmsLinked(); }
