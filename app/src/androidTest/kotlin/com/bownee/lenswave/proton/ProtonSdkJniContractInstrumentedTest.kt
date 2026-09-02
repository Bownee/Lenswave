package com.bownee.lenswave.proton

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.lang.reflect.ParameterizedType
import java.lang.reflect.WildcardType
import java.nio.ByteBuffer
import kotlin.coroutines.Continuation
import me.proton.drive.sdk.HttpSdkApi
import okhttp3.ResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import retrofit2.Response
import retrofit2.http.HTTP
import retrofit2.http.Streaming

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

    @Test
    fun protonRetrofitSuspendContractRetainsGenericReturnType() {
        var getMethod: java.lang.reflect.Method? = null
        for (method in HttpSdkApi::class.java.declaredMethods) {
            if (
                method.getAnnotation(HTTP::class.java)?.method == "GET" &&
                method.getAnnotation(Streaming::class.java) == null
            ) {
                if (getMethod != null) throw AssertionError("Expected one non-streaming GET method")
                getMethod = method
            }
        }
        val method = getMethod ?: throw AssertionError("Missing non-streaming GET method")
        val parameterTypes = method.parameterTypes
        assertEquals(Continuation::class.java, parameterTypes[parameterTypes.size - 1])

        val genericParameterTypes = method.genericParameterTypes
        val continuationType = genericParameterTypes[genericParameterTypes.size - 1]
        assertTrue(continuationType is ParameterizedType)
        val continuationArgument = (continuationType as ParameterizedType).actualTypeArguments[0]
        assertTrue(continuationArgument is WildcardType)
        val responseType = (continuationArgument as WildcardType).lowerBounds[0]
        assertTrue(responseType is ParameterizedType)
        responseType as ParameterizedType
        assertEquals(Response::class.java, responseType.rawType)
        assertEquals(ResponseBody::class.java, responseType.actualTypeArguments[0])
        assertEquals(false, HTTP::class.java.getDeclaredMethod("hasBody").defaultValue)
    }

    private data class Callback(
        val name: String,
        val returnType: Class<*>,
        val parameterTypes: Array<out Class<*>>,
    )

    private companion object {
        const val PROTON_NATIVE_CLIENT =
            "me.proton.drive.sdk.internal.ProtonDriveSdkNativeClient"

        val callbacks = arrayOf(
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
        ) = Callback(name, returnType, parameterTypes)
    }
}
