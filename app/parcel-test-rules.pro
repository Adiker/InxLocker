# Applied only with -PparcelRegressionTests=true. Instrumentation runs in a
# separate APK, so preserve its entry points in the tested app. Keep obfuscation
# of the preference code and Kotlin collections, including the EmptySet control.
-keep class androidx.tracing.** { *; }
-keep,allowobfuscation class io.github.chimio.inxlocker.util.PrefsProvider { *; }
-keep,allowobfuscation class io.github.libxposed.service.** { *; }
