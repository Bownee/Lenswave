package com.bownee.lenswave.viewer

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.system.Os
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.PathInterpolator
import androidx.core.graphics.withTranslation
import androidx.core.view.ViewCompat
import androidx.exifinterface.media.ExifInterface
import com.bownee.lenswave.ExifOrientation
import com.bownee.lenswave.R
import com.bownee.lenswave.metadata.ImageMimeSniffer
import com.bownee.lenswave.metadata.ImageOrientationPolicy
import com.bownee.lenswave.metadata.PhotoMetadataHints
import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

class FullResolutionPhotoView
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
    ) : View(context, attrs),
        Closeable {
        /** Base loads (open file, decode the down-sampled picture). */
        private val decoderExecutor = Executors.newSingleThreadExecutor()

        /**
         * Detail tiles run on their own thread so a swipe's next base decode never queues behind a
         * tile that is still decoding: `Future.cancel` cannot interrupt `decodeRegion`.
         */
        private val detailExecutor = Executors.newSingleThreadExecutor()
        private var loadFuture: Future<*>? = null
        private var detailFuture: Future<*>? = null
        private val mainHandler = Handler(Looper.getMainLooper())
        private val generation = AtomicInteger()
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val baseDestination = RectF()
        private val detailDestination = RectF()
        private val rawDestination = RectF()

        /** Maps stored pixels to the displayed picture; null while the picture is shown as stored. */
        private var orientationMatrix: Matrix? = null
        private var descriptor: ParcelFileDescriptor? = null
        private var decoder: BitmapRegionDecoder? = null
        private var rawWidth = 0
        private var rawHeight = 0
        private var imageWidth = 0
        private var imageHeight = 0
        private var rotationDegrees = 0
        private var exifOrientation = ExifInterface.ORIENTATION_NORMAL
        private var loadedUri: Uri? = null
        private var mimeType: String? = null
        private var baseBitmap: Bitmap? = null

        /** True while [baseBitmap] is a borrowed preview rather than a decoded original. */
        private var basePlaceholder = false
        private var baseSampleSize = 1
        private var detailBitmap: Bitmap? = null
        private var detailRect: Rect? = null

        /** The inputs of the tile last asked for, so an unchanged viewport costs no allocation. */
        private var detailRequested = false
        private var requestedDetailGeneration = 0
        private var requestedDetailSample = 0
        private val requestedVisible = Rect()
        private val visibleRect = Rect()

        /** Identifies the newest tile request; a decode whose serial is stale is dropped. */
        private var detailRequestSerial = 0
        private var minScale = 1f
        private var scale = 1f
        private var offsetX = 0f
        private var offsetY = 0f
        private var touchStartX = 0f
        private var touchStartY = 0f
        private var touchBlocked = false
        private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        private var zoomAnimator: ValueAnimator? = null

        private val scaleDetector =
            ScaleGestureDetector(
                context,
                object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
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
                },
            )

        private val gestureDetector =
            GestureDetector(
                context,
                object : GestureDetector.SimpleOnGestureListener() {
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
                },
            )

        fun load(
            uri: Uri,
            onComplete: (Result<Unit>) -> Unit,
        ) {
            val loadGeneration = generation.incrementAndGet()
            loadFuture?.cancel(true)
            detailFuture?.cancel(false)
            releaseDecoder()
            val metrics = resources.displayMetrics
            val baseBudget = PhotoBaseDecodePolicy.budget(width, height, metrics.widthPixels, metrics.heightPixels)
            loadFuture =
                decoderExecutor.submit {
                    runCatching {
                        var openedDescriptor: ParcelFileDescriptor? = null
                        var openedDecoder: BitmapRegionDecoder? = null
                        var openedBitmap: Bitmap? = null
                        try {
                            openedDescriptor = requireNotNull(context.contentResolver.openFileDescriptor(uri, "r"))
                            val mime = sniffMimeType(openedDescriptor)
                            // HEIF decoders rotate from the container themselves; only formats
                            // returned as stored need the EXIF tag applied on top.
                            val orientation =
                                ImageOrientationPolicy.effectiveOrientation(
                                    mime,
                                    ExifInterface(openedDescriptor.fileDescriptor)
                                        .getAttributeInt(
                                            ExifInterface.TAG_ORIENTATION,
                                            ExifInterface.ORIENTATION_NORMAL,
                                        ),
                                )
                            openedDecoder = requireNotNull(createRegionDecoder(openedDescriptor))
                            val sample =
                                PhotoBaseDecodePolicy.sampleSize(
                                    openedDecoder.width,
                                    openedDecoder.height,
                                    baseBudget,
                                )
                            // Decoded as stored; onDraw orients it with a matrix, so a rotated
                            // picture does not pay for a second full-size copy.
                            openedBitmap =
                                requireNotNull(
                                    openedDecoder.decodeRegion(
                                        Rect(0, 0, openedDecoder.width, openedDecoder.height),
                                        BitmapFactory.Options().apply {
                                            inSampleSize = sample
                                            inPreferredConfig =
                                                if (PhotoBaseDecodePolicy.isOpaque(mime)) {
                                                    Bitmap.Config.RGB_565
                                                } else {
                                                    Bitmap.Config.ARGB_8888
                                                }
                                        },
                                    ),
                                )
                            LoadedPhoto(openedDescriptor, openedDecoder, openedBitmap, sample, orientation, mime).also {
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
                            orientationMatrix = ExifOrientation.matrix(loaded.orientation)
                            loadedUri = uri
                            mimeType = loaded.mimeType
                            // A placeholder may already be zoomed; the original takes over the same
                            // rendered geometry so the picture does not jump when it arrives. Both
                            // sizes are in oriented axes: the placeholder's were swapped for its
                            // orientation when it was shown.
                            val keepGeometry = basePlaceholder && imageWidth > 0 && width > 0 && height > 0
                            val renderedWidth = imageWidth * scale
                            imageWidth = if (rotationDegrees % 180 == 0) rawWidth else rawHeight
                            imageHeight = if (rotationDegrees % 180 == 0) rawHeight else rawWidth
                            if (!basePlaceholder) baseBitmap?.recycle()
                            basePlaceholder = false
                            baseBitmap = loaded.bitmap
                            baseSampleSize = loaded.sampleSize
                            if (keepGeometry) {
                                minScale = min(width.toFloat() / imageWidth, height.toFloat() / imageHeight)
                                scale = (renderedWidth / imageWidth).coerceIn(minScale, maximumScale())
                                clampOffsets()
                                recycleDetail()
                            } else {
                                resetTransform()
                            }
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

        /**
         * Shows [bitmap] (a screen-sized preview holding its pixels as stored, to be shown under
         * the EXIF [orientation]) with full zoom and pan while the original is still being
         * decoded. It is drawn through the same matrix as a decoded original, so a rotated
         * preview costs no rotated copy. The bitmap is not owned by this view and is never
         * recycled here.
         */
        fun showPlaceholder(
            bitmap: Bitmap,
            orientation: Int = ExifInterface.ORIENTATION_NORMAL,
        ) {
            zoomAnimator?.cancel()
            zoomAnimator = null
            if (!basePlaceholder) baseBitmap?.recycle()
            basePlaceholder = true
            baseBitmap = bitmap
            rawWidth = bitmap.width
            rawHeight = bitmap.height
            rotationDegrees = ExifOrientation.degrees(orientation)
            exifOrientation = orientation
            orientationMatrix = ExifOrientation.matrix(orientation)
            loadedUri = null
            mimeType = null
            imageWidth = if (rotationDegrees % 180 == 0) rawWidth else rawHeight
            imageHeight = if (rotationDegrees % 180 == 0) rawHeight else rawWidth
            resetTransform()
            invalidate()
        }

        fun clear() {
            generation.incrementAndGet()
            zoomAnimator?.cancel()
            zoomAnimator = null
            detailFuture?.cancel(false)
            recycleDetail()
            if (!basePlaceholder) baseBitmap?.recycle()
            basePlaceholder = false
            baseBitmap = null
            rawWidth = 0
            rawHeight = 0
            imageWidth = 0
            imageHeight = 0
            exifOrientation = ExifInterface.ORIENTATION_NORMAL
            orientationMatrix = null
            loadedUri = null
            mimeType = null
            rotationDegrees = 0
            minScale = 1f
            scale = 1f
            offsetX = 0f
            offsetY = 0f
            invalidate()
            releaseDecoder()
        }

        override fun onSizeChanged(
            width: Int,
            height: Int,
            oldWidth: Int,
            oldHeight: Int,
        ) {
            super.onSizeChanged(width, height, oldWidth, oldHeight)
            if (imageWidth <= 0 || imageHeight <= 0 || width <= 0 || height <= 0) return
            if (oldWidth <= 0 || oldHeight <= 0 || minScale <= 0f) {
                resetTransform()
                return
            }
            // The usual post-open margin update must not throw a zoom away: keep the zoom relative
            // to the fit scale and the picture point under the old centre under the new one.
            val zoom = scale / minScale
            val centreImageX = (oldWidth / 2f - offsetX) / scale
            val centreImageY = (oldHeight / 2f - offsetY) / scale
            minScale = min(width.toFloat() / imageWidth, height.toFloat() / imageHeight)
            scale = (minScale * zoom).coerceIn(minScale, maximumScale())
            offsetX = width / 2f - centreImageX * scale
            offsetY = height / 2f - centreImageY * scale
            clampOffsets()
            // The current tile is still right for its part of the picture; a new one is only
            // requested when the visible region actually changed.
            scheduleDetailDecode()
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
            drawOriented(canvas, base, baseDestination)
            val detail = detailBitmap
            val sourceRect = detailRect
            if (detail != null && sourceRect != null) {
                detailDestination.set(
                    offsetX + sourceRect.left * scale,
                    offsetY + sourceRect.top * scale,
                    offsetX + sourceRect.right * scale,
                    offsetY + sourceRect.bottom * scale,
                )
                drawOriented(canvas, detail, detailDestination)
            }
        }

        /**
         * Draws [bitmap], which holds pixels as stored in the file, into [destination] in oriented
         * axes. The orientation is a rotation or flip about the centre, so the raw bitmap is drawn
         * centred on the destination under that transform; its rendered edges are the
         * destination's, swapped when the orientation turns the picture on its side.
         */
        private fun drawOriented(
            canvas: Canvas,
            bitmap: Bitmap,
            destination: RectF,
        ) {
            val transform = orientationMatrix
            if (transform == null) {
                canvas.drawBitmap(bitmap, null, destination, paint)
                return
            }
            val swapped = ExifOrientation.swapsAxes(exifOrientation)
            val halfWidth = (if (swapped) destination.height() else destination.width()) / 2f
            val halfHeight = (if (swapped) destination.width() else destination.height()) / 2f
            rawDestination.set(-halfWidth, -halfHeight, halfWidth, halfHeight)
            canvas.withTranslation(destination.centerX(), destination.centerY()) {
                concat(transform)
                drawBitmap(bitmap, null, rawDestination, paint)
            }
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            val isClick =
                event.actionMasked == MotionEvent.ACTION_UP &&
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

        /**
         * What this view already knows about the decoded original at [uri]: its stored size, EXIF
         * rotation and container format. Null while a placeholder or a different file is showing.
         */
        fun metadataHints(uri: Uri): PhotoMetadataHints? {
            if (decoder == null || basePlaceholder || loadedUri != uri) return null
            return PhotoMetadataHints(rawWidth, rawHeight, rotationDegrees, mimeType)
        }

        fun zoomIn() = setZoom((scale * 1.5f).coerceAtMost(maximumScale()))

        fun zoomOut() = setZoom((scale / 1.5f).coerceAtLeast(minScale))

        fun resetZoom() = setZoom(minScale)

        /** How far the picture is zoomed past its fit: 1 at fit scale, up to the maximum; 1 while nothing is shown. */
        fun zoomFactor(): Float = if (imageWidth <= 0 || minScale <= 0f) 1f else scale / minScale

        /** Re-applies a [zoomFactor] to the loaded picture, about its centre; the exact pan is not kept. */
        fun restoreZoomFactor(factor: Float) = setZoom(minScale * factor)

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

        private fun animateDoubleTapZoom(
            focusX: Float,
            focusY: Float,
        ) {
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
            zoomAnimator =
                ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = DOUBLE_TAP_ZOOM_DURATION_MILLIS
                    interpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
                    addUpdateListener { animator ->
                        val progress = animator.animatedValue as Float
                        scale = startScale + (targetScale - startScale) * progress
                        offsetX = startOffsetX + (targetOffsetX - startOffsetX) * progress
                        offsetY = startOffsetY + (targetOffsetY - startOffsetY) * progress
                        invalidate()
                    }
                    addListener(
                        object : AnimatorListenerAdapter() {
                            override fun onAnimationEnd(animation: Animator) {
                                zoomAnimator = null
                                scheduleDetailDecode()
                            }
                        },
                    )
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

                MotionEvent.ACTION_POINTER_DOWN -> {
                    touchBlocked = true
                }
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
            offsetX =
                if (renderedWidth <= width) {
                    (width - renderedWidth) / 2f
                } else {
                    offsetX.coerceIn(width - renderedWidth, 0f)
                }
            offsetY =
                if (renderedHeight <= height) {
                    (height - renderedHeight) / 2f
                } else {
                    offsetY.coerceIn(height - renderedHeight, 0f)
                }
        }

        private fun scheduleDetailDecode() {
            val activeDecoder = decoder ?: return
            if (width == 0 || height == 0) return
            val displaySample = PhotoDetailDecodePolicy.sampleSize(scale, baseSampleSize)
            if (displaySample == null) {
                // The base already matches the display; nothing to sharpen.
                if (detailRequested || detailBitmap != null) {
                    recycleDetail()
                    invalidate()
                }
                return
            }
            val detailGeneration = generation.get()
            fillVisibleImageRect(visibleRect)
            // Every touch-up, animation end and size change lands here; the common case is that
            // nothing moved, decided before any Region, Plan, Rect or String exists.
            if (detailRequested &&
                requestedDetailGeneration == detailGeneration &&
                requestedDetailSample == displaySample &&
                requestedVisible == visibleRect
            ) {
                return
            }
            val visible = visibleRect
            val plan =
                PhotoDetailDecodePolicy.plan(
                    scale = scale,
                    baseSampleSize = baseSampleSize,
                    visible = PhotoDetailDecodePolicy.Region(visible.left, visible.top, visible.right, visible.bottom),
                    imageWidth = imageWidth,
                    imageHeight = imageHeight,
                    budgetPixels = PhotoDetailDecodePolicy.budget(width, height),
                )
            if (plan == null) {
                recycleDetail()
                invalidate()
                return
            }
            val sample = plan.sampleSize
            val region = plan.region
            val rawRect =
                alignRect(
                    orientedToRaw(Rect(region.left, region.top, region.right, region.bottom)),
                    sample,
                    rawWidth,
                    rawHeight,
                )
            val orientedRect = rawToOriented(rawRect)
            detailRequested = true
            requestedDetailGeneration = detailGeneration
            requestedDetailSample = displaySample
            requestedVisible.set(visibleRect)
            val serial = ++detailRequestSerial
            detailFuture?.cancel(false)
            detailFuture =
                detailExecutor.submit {
                    // A newer load or clear has already retired this decoder (its recycle is queued
                    // behind this task), so a stale tile is not worth decoding.
                    if (detailGeneration != generation.get() || activeDecoder.isRecycled) return@submit
                    // Tiles stay in stored axes as well; onDraw orients them like the base.
                    val decoded =
                        runCatching {
                            activeDecoder.decodeRegion(
                                rawRect,
                                BitmapFactory.Options().apply {
                                    inSampleSize = sample
                                    inPreferredConfig = Bitmap.Config.ARGB_8888
                                },
                            )
                        }.getOrNull()
                    mainHandler.post {
                        if (detailGeneration != generation.get() || !detailRequested || detailRequestSerial != serial) {
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

        /** The viewport in oriented image pixels, written into [target]. */
        private fun fillVisibleImageRect(target: Rect) {
            val left = floor((-offsetX / scale).coerceAtLeast(0f)).toInt()
            val top = floor((-offsetY / scale).coerceAtLeast(0f)).toInt()
            val right = ceil(((width - offsetX) / scale).coerceAtMost(imageWidth.toFloat())).toInt()
            val bottom = ceil(((height - offsetY) / scale).coerceAtMost(imageHeight.toFloat())).toInt()
            target.set(left, top, max(left + 1, right), max(top + 1, bottom))
        }

        private fun orientedToRaw(rect: Rect): Rect =
            ExifOrientation.orientedToRaw(rect, exifOrientation, rawWidth, rawHeight)

        private fun rawToOriented(rect: Rect): Rect =
            ExifOrientation.rawToOriented(rect, exifOrientation, rawWidth, rawHeight)

        private fun alignRect(
            rect: Rect,
            sample: Int,
            maximumWidth: Int,
            maximumHeight: Int,
        ): Rect {
            val left = (rect.left / sample * sample).coerceIn(0, maximumWidth - 1)
            val top = (rect.top / sample * sample).coerceIn(0, maximumHeight - 1)
            val right = (ceil(rect.right.toDouble() / sample).toInt() * sample).coerceIn(left + 1, maximumWidth)
            val bottom = (ceil(rect.bottom.toDouble() / sample).toInt() * sample).coerceIn(top + 1, maximumHeight)
            return Rect(left, top, right, bottom)
        }

        /** Reads the container signature with a positional read so the descriptor's offset is untouched. */
        private fun sniffMimeType(descriptor: ParcelFileDescriptor): String? =
            runCatching {
                val header = ByteArray(ImageMimeSniffer.HEADER_LENGTH)
                val read = Os.pread(descriptor.fileDescriptor, header, 0, header.size, 0L)
                ImageMimeSniffer.sniff(header.copyOf(read.coerceAtLeast(0)))
            }.getOrNull()

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
            detailRequested = false
        }

        override fun close() {
            generation.incrementAndGet()
            zoomAnimator?.cancel()
            zoomAnimator = null
            recycleDetail()
            // A placeholder belongs to the preview cache and may be handed to the next viewer.
            if (!basePlaceholder) baseBitmap?.recycle()
            basePlaceholder = false
            baseBitmap = null
            loadFuture?.cancel(true)
            detailFuture?.cancel(false)
            // The decoder is recycled on the detail thread, queued behind any tile still decoding:
            // decodeRegion cannot be interrupted and recycling underneath it crashes natively, so
            // the executor drains its queue instead of being torn down on a timer.
            releaseDecoder()
            decoderExecutor.shutdownNow()
            detailExecutor.shutdown()
        }

        /**
         * Retires the current decoder from the main thread. The recycle runs on the detail thread,
         * queued behind any tile still decoding, so neither the main thread nor the next base load
         * waits for it, and no tile ever runs against a recycled decoder.
         */
        private fun releaseDecoder() {
            val retiredDecoder = decoder ?: return
            val retiredDescriptor = descriptor
            decoder = null
            descriptor = null
            val release =
                Runnable {
                    retiredDecoder.recycle()
                    retiredDescriptor?.close()
                }
            if (detailExecutor.isShutdown) release.run() else detailExecutor.execute(release)
        }

        private data class LoadedPhoto(
            val descriptor: ParcelFileDescriptor,
            val decoder: BitmapRegionDecoder,
            val bitmap: Bitmap,
            val sampleSize: Int,
            val orientation: Int,
            val mimeType: String?,
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
        }
    }
