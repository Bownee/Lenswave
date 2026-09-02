package com.bownee.lenswave.proton

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProtonSdkJniContractInstrumentedTest {
    @Test
    fun nativeCallbacksRetainTheirNamesAndSignatures() {
        val classLoader = InstrumentationRegistry.getInstrumentation().targetContext.classLoader
        val bridge = classLoader.loadClass(PROTON_NATIVE_CLIENT)

        callbacks.forEach { callback ->
            val method = bridge.getDeclaredMethod(callback.name, *callback.parameterTypes)
            assertEquals(callback.returnType, method.returnType)
        }
    }

    private data class Callback(
        val name: String,
        val returnType: Class<*>,
        val parameterTypes: Array<Class<*>>,
    )

    private companion object {
        const val PROTON_NATIVE_CLIENT =
            "me.proton.drive.sdk.internal.ProtonDriveSdkNativeClient"

        val callbacks = listOf(
            callback("onResponse", Void.TYPE, ByteBuffer::class.java),
            callback("onCallback", Void.TYPE, ByteBuffer::class.java),
            callback("onRead", java.lang.Long.TYPE, ByteBuffer::class.java, java.lang.Long.TYPE),
            callback("onWrite", java.lang.Long.TYPE, ByteBuffer::class.java, java.lang.Long.TYPE),
            callback("onSeek", Void.TYPE, ByteBuffer::class.java, java.lang.Long.TYPE),
            callback("onYield", Void.TYPE, ByteBuffer::class.java),
            callback("onProgress", Void.TYPE, ByteBuffer::class.java),
            callback(
                "onSendHttpRequest",
                java.lang.Long.TYPE,
                ByteBuffer::class.java,
                java.lang.Long.TYPE,
            ),
            callback("onHttpResponseRead", Void.TYPE, ByteBuffer::class.java, java.lang.Long.TYPE),
            callback("onAccountRequest", Void.TYPE, ByteBuffer::class.java, java.lang.Long.TYPE),
            callback("onRecordMetric", Void.TYPE, ByteBuffer::class.java),
            callback("onFeatureEnabled", java.lang.Long.TYPE, ByteBuffer::class.java),
            callback("onSha1", Void.TYPE, ByteBuffer::class.java),
            callback("onDispose", Void.TYPE),
        )

        fun callback(
            name: String,
            returnType: Class<*>,
            vararg parameterTypes: Class<*>,
        ) = Callback(name, returnType, arrayOf(*parameterTypes))
    }
}
