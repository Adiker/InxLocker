# Applied only with -PparcelRegressionTests=true. Instrumentation runs in a
# separate APK, so preserve its entry points and shared Kotlin runtime in the
# tested app. The boot class loader still cannot load Kotlin's EmptySet.
-keep class androidx.tracing.** { *; }
-keep class kotlin.** { *; }
-keep,allowobfuscation class io.github.chimio.inxlocker.util.PrefsProvider { *; }
-keep class io.github.libxposed.service.** { *; }
# The UI test reads this resource ID from the separate instrumentation APK.
-keep class io.github.chimio.inxlocker.R$string { public static int installer_system_default; }
