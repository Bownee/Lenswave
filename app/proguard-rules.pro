# Proton's native bridge resolves these Kotlin callbacks by their literal names. The SDK's
# consumer rules preserve its native entry points, but not the non-native methods called from C.
-keep class me.proton.drive.sdk.internal.ProtonDriveSdkNativeClient {
    *;
}
