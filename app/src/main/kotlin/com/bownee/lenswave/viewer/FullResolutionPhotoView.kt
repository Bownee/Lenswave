package com.bownee.lenswave.viewer

import com.bownee.lenswave.ExifOrientation
import com.bownee.lenswave.R
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.PathInterpolator
import androidx.exifinterface.media.ExifInterface
import androidx.core.view.ViewCompat
import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.ceil
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

class FullResolutionPhotoView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs), Closeable {
    private val decoderExecutor = Executors.newSingleThreadExecutor()
    private var loadFuture: Future<*>? = null
    private var detailFuture: Future<*>? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val generation = AtomicInteger()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val baseDestination = RectF()
    private val detailDestination = RectF()
    private var descriptor: ParcelFileDescriptor? = null
    private var decoder: BitmapRegionDecoder? = null
    private var rawWidth = 0
    private var rawHeight = 0
    private var imageWidth = 0
    private var imageHeight = 0
    private var rotationDegrees = 0
    private var exifOrientation = ExifInterface.ORIENTATION_NORMAL
    private var baseBitmap: Bitmap? = null
    private var baseSampleSize = 1
    private var detailBitmap: Bitmap? = null
    private var detailRect: Rect? = null
    private var requestedDetailKey: String? = null
    private var minScale = 1f
    private var scale = 1f
    private var offsetX = 0f
    private var offsetY = 0f
    private var touchStartX = 0f
    private var touchStartY = 0f
    private var touchBlocked = false
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var zoomAnimator: ValueAnimator? = null

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            zoomAnimator?.cancel()
            return true
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val previous = scale
            val maximum = max(1f, minScale * 8f)
            scale = (scale * detector.scaleFactor).coerceIn(minScale, maximum)
            val ratio = scale / previous
            offsetX = detector.focusX - (detector.focusX - offsetX) * ratio
            offsetY = detector.focusY - (detector.focusY - offsetY) * ratio
            clampOffsets()
            invalidate()
            return true
        }
    })

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(event: MotionEvent): Boolean {
            zoomAnimator?.cancel()
            return true
        }

        override fun onScroll(
            first: MotionEvent?,
            current: MotionEvent,
            distanceX: Float,
            distanceY: Float,
        ): Boolean {
            if (isAtFitScale()) return true
            offsetX -= distanceX
            offsetY -= distanceY
            clampOffsets()
            invalidate()
            return true
        }

        override fun onDoubleTap(event: MotionEvent): Boolean = true

        override fun onDoubleTapEvent(event: MotionEvent): Boolean {
            // GestureDetector dispatches the second tap's onDown after onDoubleTap. Starting on
            // ACTION_UP prevents onDown from immediately cancelling the new zoom animation.
            if (event.actionMasked == MotionEvent.ACTION_UP) animateDoubleTapZoom(event.x, event.y)
            return true
        }

    })

    fun load(uri: Uri, onComplete: (Result<Unit>) -> Unit) {
        val loadGeneration = generation.incrementAndGet()
        loadFuture?.cancel(true)
        detailFuture?.cancel(true)
        loadFuture = decoderExecutor.submit {
            runCatching {
                releaseDecoder()
                var openedDescriptor: ParcelFileDescriptor? = null
                var openedDecoder: BitmapRegionDecoder? = null
                var openedBitmap: Bitmap? = null
                try {
                    openedDescriptor = requireNotNull(context.contentResolver.openFileDescriptor(uri, "r"))
                    val orientation = ExifInterface(openedDescriptor.fileDescriptor)
                        .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                    openedDecoder = requireNotNull(createRegionDecoder(openedDescriptor))
                    val sample = calculateBaseSample(openedDecoder.width, openedDecoder.height)
                    val rawBase = requireNotNull(openedDecoder.decodeRegion(
                        Rect(0, 0, openedDecoder.width, openedDecoder.height),
                        BitmapFactory.Options().apply {
                            inSampleSize = sample
                            inPreferredConfig = Bitmap.Config.ARGB_8888
                        },
                    ))
                    openedBitmap = ExifOrientation.apply(rawBase, orientation, true)
                    LoadedPhoto(openedDescriptor, openedDecoder, openedBitmap, sample, orientation).also {
                        openedDescriptor = null
                        openedDecoder = null
                        openedBitmap = null
                    }
                } finally {
                    openedBitmap?.recycle()
                    openedDecoder?.recycle()
                    openedDescriptor?.close()
                }
            }.onSuccess { loaded ->
                mainHandler.post {
                    if (loadGeneration != generation.get()) {
                        loaded.close()
                        return@post
                    }
                    descriptor = loaded.descriptor
                    decoder = loaded.decoder
                    rawWidth = loaded.decoder.width
                    rawHeight = loaded.decoder.height
                    rotationDegrees = ExifOrientation.degrees(loaded.orientation)
                    exifOrientation = loaded.orientation
                    imageWidth = if (rotationDegrees % 180 == 0) rawWidth else rawHeight
                    imageHeight = if (rotationDegrees % 180 == 0) rawHeight else rawWidth
                    baseBitmap?.recycle()
                    baseBitmap = loaded.bitmap
                    baseSampleSize = loaded.sampleSize
                    resetTransform()
                    invalidate()
                    scheduleDetailDecode()
                    onComplete(Result.success(Unit))
                }
            }.onFailure { error ->
                mainHandler.post {
                    if (loadGeneration == generation.get()) onComplete(Result.failure(error))
                }
            }
        }
    }

    fun clear() {
        generation.incrementAndGet()
        zoomAnimator?.cancel()
        zoomAnimator = null
        requestedDetailKey = null
        detailFuture?.cancel(true)
        detailRect = null
        detailBitmap?.recycle()
        detailBitmap = null
        baseBitmap?.recycle()
        baseBitmap = null
        rawWidth = 0
        rawHeight = 0
        imageWidth = 0
        imageHeight = 0
        exifOrientation = ExifInterface.ORIENTATION_NORMAL
        rotationDegrees = 0
        minScale = 1f
        scale = 1f
        offsetX = 0f
        offsetY = 0f
        invalidate()
        if (!decoderExecutor.isShutdown) decoderExecutor.execute(::releaseDecoder)
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        if (imageWidth > 0 && imageHeight > 0) resetTransform()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val base = baseBitmap ?: return
        baseDestination.set(
            offsetX,
            offsetY,
            offsetX + imageWidth * scale,
            offsetY + imageHeight * scale,
        )
        canvas.drawBitmap(base, null, baseDestination, paint)
        val detail = detailBitmap
        val sourceRect = detailRect
        if (detail != null && sourceRect != null) {
            detailDestination.set(
                offsetX + sourceRect.left * scale,
                offsetY + sourceRect.top * scale,
                offsetX + sourceRect.right * scale,
                offsetY + sourceRect.bottom * scale,
            )
            canvas.drawBitmap(detail, null, detailDestination, paint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val isClick = event.actionMasked == MotionEvent.ACTION_UP &&
            !touchBlocked &&
            max(
                abs(touchStartX - event.rawX),
                abs(touchStartY - event.rawY),
            ) < touchSlop
        trackTouchStart(event)
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        if (isClick) performClick()
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            scheduleDetailDecode()
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    fun isAtFitScale(): Boolean = scale <= minScale * 1.15f

    fun zoomIn() = setZoom((scale * 1.5f).coerceAtMost(maximumScale()))

    fun zoomOut() = setZoom((scale / 1.5f).coerceAtLeast(minScale))

    fun resetZoom() = setZoom(minScale)

    private fun setZoom(targetScale: Float) {
        if (imageWidth <= 0 || imageHeight <= 0 || width <= 0 || height <= 0) return
        zoomAnimator?.cancel()
        val previous = scale.coerceAtLeast(0.0001f)
        scale = targetScale.coerceIn(minScale, maximumScale())
        val ratio = scale / previous
        val focusX = width / 2f
        val focusY = height / 2f
        offsetX = focusX - (focusX - offsetX) * ratio
        offsetY = focusY - (focusY - offsetY) * ratio
        clampOffsets()
        ViewCompat.setStateDescription(
            this,
            context.getString(R.string.zoom_percent, (scale / minScale * 100f).toInt()),
        )
        invalidate()
        scheduleDetailDecode()
    }

    private fun maximumScale(): Float = max(1f, minScale * 8f)

    fun fittedImageBottom(): Float? {
        if (width <= 0 || height <= 0 || imageWidth <= 0 || imageHeight <= 0) return null
        val fittedScale = min(width.toFloat() / imageWidth, height.toFloat() / imageHeight)
        val fittedHeight = imageHeight * fittedScale
        return (height + fittedHeight) / 2f
    }

    private fun animateDoubleTapZoom(focusX: Float, focusY: Float) {
        val startScale = scale
        val startOffsetX = offsetX
        val startOffsetY = offsetY
        val inspectionScale = (minScale * DOUBLE_TAP_ZOOM).coerceIn(minScale, maximumScale())
        val targetScale = if (isAtFitScale()) inspectionScale else minScale
        scale = targetScale
        val ratio = targetScale / startScale
        offsetX = focusX - (focusX - offsetX) * ratio
        offsetY = focusY - (focusY - offsetY) * ratio
        clampOffsets()
        val targetOffsetX = offsetX
        val targetOffsetY = offsetY
        scale = startScale
        offsetX = startOffsetX
        offsetY = startOffsetY

        zoomAnimator?.cancel()
        zoomAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = DOUBLE_TAP_ZOOM_DURATION_MILLIS
            interpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
            addUpdateListener { animator ->
                val progress = animator.animatedValue as Float
                scale = startScale + (targetScale - startScale) * progress
                offsetX = startOffsetX + (targetOffsetX - startOffsetX) * progress
                offsetY = startOffsetY + (targetOffsetY - startOffsetY) * progress
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    zoomAnimator = null
                    scheduleDetailDecode()
                }
            })
            start()
        }
    }

    private fun trackTouchStart(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchStartX = event.rawX
                touchStartY = event.rawY
                touchBlocked = false
            }
            MotionEvent.ACTION_POINTER_DOWN -> touchBlocked = true
        }
    }

    private fun resetTransform() {
        if (width == 0 || height == 0 || imageWidth == 0 || imageHeight == 0) return
        minScale = min(width.toFloat() / imageWidth, height.toFloat() / imageHeight)
        scale = minScale
        offsetX = (width - imageWidth * scale) / 2f
        offsetY = (height - imageHeight * scale) / 2f
        recycleDetail()
    }

    private fun clampOffsets() {
        val renderedWidth = imageWidth * scale
        val renderedHeight = imageHeight * scale
        offsetX = if (renderedWidth <= width) {
            (width - renderedWidth) / 2f
        } else {
            offsetX.coerceIn(width - renderedWidth, 0f)
        }
        offsetY = if (renderedHeight <= height) {
            (height - renderedHeight) / 2f
        } else {
            offsetY.coerceIn(height - renderedHeight, 0f)
        }
    }

    private fun scheduleDetailDecode() {
        val activeDecoder = decoder ?: return
        if (width == 0 || height == 0) return
        var sample = 1
        while (sample * 2 < baseSampleSize && scale * sample * 2 <= 1f) sample *= 2
        if (sample >= baseSampleSize) {
            recycleDetail()
            invalidate()
            return
        }
        val visible = visibleImageRect()
        val marginX = visible.width() / 4
        val marginY = visible.height() / 4
        visible.inset(-marginX, -marginY)
        if (!visible.intersect(0, 0, imageWidth, imageHeight)) return
        val rawRect = alignRect(orientedToRaw(visible), sample, rawWidth, rawHeight)
        val orientedRect = rawToOriented(rawRect)
        val key = "${generation.get()}:$sample:${rawRect.flattenToString()}"
        if (key == requestedDetailKey) return
        requestedDetailKey = key
        val detailGeneration = generation.get()
        detailFuture?.cancel(true)
        detailFuture = decoderExecutor.submit {
            val decoded = runCatching {
                val raw = requireNotNull(activeDecoder.decodeRegion(
                    rawRect,
                    BitmapFactory.Options().apply {
                        inSampleSize = sample
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                    },
                ))
                ExifOrientation.apply(raw, exifOrientation, true)
            }.getOrNull()
            mainHandler.post {
                if (detailGeneration != generation.get() || requestedDetailKey != key) {
                    decoded?.recycle()
                    return@post
                }
                detailBitmap?.recycle()
                detailBitmap = decoded
                detailRect = if (decoded == null) null else orientedRect
                invalidate()
            }
        }
    }

    private fun visibleImageRect(): Rect {
        val left = floor((-offsetX / scale).coerceAtLeast(0f)).toInt()
        val top = floor((-offsetY / scale).coerceAtLeast(0f)).toInt()
        val right = ceil(((width - offsetX) / scale).coerceAtMost(imageWidth.toFloat())).toInt()
        val bottom = ceil(((height - offsetY) / scale).coerceAtMost(imageHeight.toFloat())).toInt()
        return Rect(left, top, max(left + 1, right), max(top + 1, bottom))
    }

    private fun orientedToRaw(rect: Rect): Rect =
        ExifOrientation.orientedToRaw(rect, exifOrientation, rawWidth, rawHeight)

    private fun rawToOriented(rect: Rect): Rect =
        ExifOrientation.rawToOriented(rect, exifOrientation, rawWidth, rawHeight)

    private fun alignRect(rect: Rect, sample: Int, maximumWidth: Int, maximumHeight: Int): Rect {
        val left = (rect.left / sample * sample).coerceIn(0, maximumWidth - 1)
        val top = (rect.top / sample * sample).coerceIn(0, maximumHeight - 1)
        val right = (ceil(rect.right.toDouble() / sample).toInt() * sample).coerceIn(left + 1, maximumWidth)
        val bottom = (ceil(rect.bottom.toDouble() / sample).toInt() * sample).coerceIn(top + 1, maximumHeight)
        return Rect(left, top, right, bottom)
    }

    private fun calculateBaseSample(width: Int, height: Int): Int {
        var sample = 1
        while ((width / sample).toLong() * (height / sample) > MAX_BASE_PIXELS) sample *= 2
        return sample
    }

    @Suppress("DEPRECATION")
    private fun createRegionDecoder(descriptor: ParcelFileDescriptor): BitmapRegionDecoder =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            BitmapRegionDecoder.newInstance(descriptor)
        } else {
            BitmapRegionDecoder.newInstance(descriptor.fileDescriptor, false)
        }

    private fun recycleDetail() {
        detailBitmap?.recycle()
        detailBitmap = null
        detailRect = null
        requestedDetailKey = null
    }

    override fun close() {
        generation.incrementAndGet()
        zoomAnimator?.cancel()
        zoomAnimator = null
        recycleDetail()
        baseBitmap?.recycle()
        baseBitmap = null
        loadFuture?.cancel(true)
        detailFuture?.cancel(true)
        decoderExecutor.shutdownNow()
        Thread {
            decoderExecutor.awaitTermination(2, TimeUnit.SECONDS)
            releaseDecoder()
        }.apply {
            name = "Lenswave-photo-decoder-cleanup"
            isDaemon = true
            start()
        }
    }

    private fun releaseDecoder() {
        decoder?.recycle()
        decoder = null
        descriptor?.close()
        descriptor = null
    }

    private data class LoadedPhoto(
        val descriptor: ParcelFileDescriptor,
        val decoder: BitmapRegionDecoder,
        val bitmap: Bitmap,
        val sampleSize: Int,
        val orientation: Int,
    ) : Closeable {
        override fun close() {
            bitmap.recycle()
            decoder.recycle()
            descriptor.close()
        }
    }

    private companion object {
        const val DOUBLE_TAP_ZOOM = 3f
        const val DOUBLE_TAP_ZOOM_DURATION_MILLIS = 360L
        const val MAX_BASE_PIXELS = 4_000_000L
    }
}
