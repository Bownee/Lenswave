# Proton's native bridge resolves these Kotlin callbacks by their literal names. The SDK's
# consumer rules preserve its native entry points, but not the non-native methods called from C.
-keep class me.proton.drive.sdk.internal.ProtonDriveSdkNativeClient {
    *;
}

# Retrofit 2.9.0's bundled consumer rules predate these R8 full-mode contracts.
# Proton's HttpSdkApi uses suspend methods, so Retrofit must be able to reflect the
# Continuation type argument and the generic Response type after optimization.
-keepattributes AnnotationDefault
-if interface * { @retrofit2.http.* <methods>; }
-keep,allowobfuscation interface * extends <1>
-keep,allowoptimization,allowshrinking,allowobfuscation class kotlin.coroutines.Continuation
-if interface * { @retrofit2.http.* public *** *(...); }
-keep,allowoptimization,allowshrinking,allowobfuscation class <3>
-keep,allowoptimization,allowshrinking,allowobfuscation class retrofit2.Response
