# Applied only with -PparcelRegressionTests=true. Instrumentation runs in a
# separate APK, so preserve its entry points and shared Kotlin runtime in the
# tested app. The boot class loader still cannot load Kotlin's EmptySet.
-keep class androidx.tracing.** { *; }
-keep class kotlin.** { *; }
-keep,allowobfuscation class io.github.chimio.inxlocker.util.PrefsProvider { *; }
-keep class io.github.libxposed.service.** { *; }
