package com.bownee.lenswave

import android.content.Context
import android.graphics.Bitmap
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.util.AttributeSet
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.max

internal class PhotoGLSurfaceView @JvmOverloads constructor(
    context: Context,
    attributes: AttributeSet? = null,
) : GLSurfaceView(context, attributes) {
    private val photoRenderer = PhotoRenderer()

    init {
        setEGLContextClientVersion(2)
        preserveEGLContextOnPause = true
        setRenderer(photoRenderer)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    fun setBitmap(bitmap: Bitmap, onUploaded: Runnable) {
        photoRenderer.setBitmap(bitmap, Runnable { post(onUploaded) })
        requestRender()
    }

    fun setAdjustments(adjustments: PhotoAdjustments) {
        photoRenderer.setAdjustments(adjustments)
        requestRender()
    }

    private class PhotoRenderer : Renderer {
        private val positionBuffer = allocate(8)
        private val textureBuffer = allocate(TEXTURE_COORDINATES)
        private val texture = IntArray(1)

        @Volatile private var currentBitmap: Bitmap? = null
        @Volatile private var pendingBitmap: PendingBitmap? = null
        @Volatile private var adjustments = PhotoAdjustments.NEUTRAL
        private var program = 0
        private var surfaceWidth = 0
        private var surfaceHeight = 0
        private var imageWidth = 0
        private var imageHeight = 0

        override fun onSurfaceCreated(unused: GL10?, config: EGLConfig?) {
            GLES20.glClearColor(0.02f, 0.025f, 0.035f, 1f)
            program = createProgram(VERTEX_SHADER, PhotoAdjustmentSpec.FRAGMENT_SHADER)
            GLES20.glGenTextures(1, texture, 0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture[0])
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            currentBitmap?.let { retained -> pendingBitmap = PendingBitmap(retained, Runnable {}) }
        }

        override fun onSurfaceChanged(unused: GL10?, width: Int, height: Int) {
            surfaceWidth = width
            surfaceHeight = height
            GLES20.glViewport(0, 0, width, height)
        }

        override fun onDrawFrame(unused: GL10?) {
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            uploadPendingBitmap()
            if (imageWidth == 0 || imageHeight == 0) return

            val current = adjustments
            updatePositions(current.rotationQuarterTurns)
            GLES20.glUseProgram(program)

            val positionLocation = GLES20.glGetAttribLocation(program, "a_position")
            GLES20.glEnableVertexAttribArray(positionLocation)
            GLES20.glVertexAttribPointer(positionLocation, 2, GLES20.GL_FLOAT, false, 0, positionBuffer)

            val textureCoordinateLocation = GLES20.glGetAttribLocation(program, "a_texCoord")
            GLES20.glEnableVertexAttribArray(textureCoordinateLocation)
            GLES20.glVertexAttribPointer(textureCoordinateLocation, 2, GLES20.GL_FLOAT, false, 0, textureBuffer)

            setUniform("u_brightness", current.brightness)
            setUniform("u_contrast", current.contrast)
            setUniform("u_highlights", current.highlights)
            setUniform("u_shadows", current.shadows)
            setUniform("u_saturation", current.saturation)
            setUniform("u_warmth", current.warmth)
            setUniform("u_tint", current.tint)
            setUniform("u_vignette", current.vignette)
            GLES20.glUniform1i(
                GLES20.glGetUniformLocation(program, "u_rotation"),
                current.rotationQuarterTurns,
            )

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture[0])
            GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "u_texture"), 0)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }

        fun setBitmap(bitmap: Bitmap, onUploaded: Runnable) {
            pendingBitmap = PendingBitmap(bitmap, onUploaded)
        }

        fun setAdjustments(adjustments: PhotoAdjustments) {
            this.adjustments = adjustments
        }

        private fun uploadPendingBitmap() {
            val pending = pendingBitmap ?: return
            if (pending.bitmap.isRecycled) return
            pendingBitmap = null
            imageWidth = pending.bitmap.width
            imageHeight = pending.bitmap.height
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture[0])
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, pending.bitmap, 0)
            currentBitmap = pending.bitmap
            pending.onUploaded.run()
        }

        private fun updatePositions(rotationQuarterTurns: Int) {
            val displayedWidth = if (rotationQuarterTurns % 2 == 0) imageWidth else imageHeight
            val displayedHeight = if (rotationQuarterTurns % 2 == 0) imageHeight else imageWidth
            val imageAspect = displayedWidth.toFloat() / displayedHeight
            val surfaceAspect = surfaceWidth.toFloat() / max(1, surfaceHeight)
            var horizontalScale = 1f
            var verticalScale = 1f
            if (imageAspect > surfaceAspect) {
                verticalScale = surfaceAspect / imageAspect
            } else {
                horizontalScale = imageAspect / surfaceAspect
            }
            positionBuffer.position(0)
            positionBuffer.put(
                floatArrayOf(
                    -horizontalScale,
                    -verticalScale,
                    horizontalScale,
                    -verticalScale,
                    -horizontalScale,
                    verticalScale,
                    horizontalScale,
                    verticalScale,
                ),
            )
            positionBuffer.position(0)
        }

        private fun setUniform(name: String, value: Float) {
            GLES20.glUniform1f(GLES20.glGetUniformLocation(program, name), value)
        }

        private data class PendingBitmap(val bitmap: Bitmap, val onUploaded: Runnable)

        companion object {
            private const val VERTEX_SHADER = """
                attribute vec2 a_position;
                attribute vec2 a_texCoord;
                varying vec2 v_texCoord;
                void main() {
                    gl_Position = vec4(a_position, 0.0, 1.0);
                    v_texCoord = a_texCoord;
                }
            """

            private val TEXTURE_COORDINATES = floatArrayOf(
                0f, 1f,
                1f, 1f,
                0f, 0f,
                1f, 0f,
            )

            private fun allocate(size: Int): FloatBuffer = ByteBuffer.allocateDirect(size * Float.SIZE_BYTES)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()

            private fun allocate(values: FloatArray): FloatBuffer = allocate(values.size).apply {
                put(values)
                position(0)
            }

            private fun createProgram(vertexSource: String, fragmentSource: String): Int {
                val vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource)
                val fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
                val result = GLES20.glCreateProgram()
                GLES20.glAttachShader(result, vertexShader)
                GLES20.glAttachShader(result, fragmentShader)
                GLES20.glLinkProgram(result)
                val status = IntArray(1)
                GLES20.glGetProgramiv(result, GLES20.GL_LINK_STATUS, status, 0)
                if (status[0] == 0) {
                    val message = GLES20.glGetProgramInfoLog(result)
                    GLES20.glDeleteProgram(result)
                    error("OpenGL program link failed: $message")
                }
                GLES20.glDeleteShader(vertexShader)
                GLES20.glDeleteShader(fragmentShader)
                return result
            }

            private fun compileShader(type: Int, source: String): Int {
                val shader = GLES20.glCreateShader(type)
                GLES20.glShaderSource(shader, source)
                GLES20.glCompileShader(shader)
                val status = IntArray(1)
                GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
                if (status[0] == 0) {
                    val message = GLES20.glGetShaderInfoLog(shader)
                    GLES20.glDeleteShader(shader)
                    error("OpenGL shader compilation failed: $message")
                }
                return shader
            }
        }
    }
}
