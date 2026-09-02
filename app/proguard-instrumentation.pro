# The separately shrunk test APK references this test-only startup hook, so R8
# cannot discover the reference while optimizing the minified app under test.
-keepclassmembers class com.bownee.lenswave.LenswaveApplication {
    public static final com.bownee.lenswave.LenswaveApplication$Companion Companion;
}
-keep class com.bownee.lenswave.LenswaveApplication$Companion {
    *;
}

# The separately packaged AndroidJUnitRunner expects this compile-time dependency
# in the target app, where release-mode R8 cannot see the cross-APK reference.
-keep class androidx.tracing.Trace {
    *;
}
-keep class kotlin.LazyKt {
    *;
}
