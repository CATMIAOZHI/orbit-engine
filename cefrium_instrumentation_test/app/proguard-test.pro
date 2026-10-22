# Rules for the instrumentation APK when the suite runs against the minified
# app (run-instrumentation-suite.sh --minified). androidx.test references
# errorprone annotations it does not ship.
-dontwarn com.google.errorprone.annotations.**
