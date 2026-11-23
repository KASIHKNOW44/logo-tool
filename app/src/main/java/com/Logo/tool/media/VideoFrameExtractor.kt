package com.Logo.tool.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

data class NativeBootAnimOptions(
    val width: Int,
    val height: Int,
    val fps: Int,
    val secs: Double,
    val fitMode: String, // "cover" | "contain" | "stretch"
    val partName: String
)

data class NativeBootAnimResult(
    val outputZip: File,
    val frameCount: Int,
    val decodeMs: Long,
    val drawMs: Long,
    val encodeMs: Long,
    val zipMs: Long,
    val totalMs: Long,
    val usedFallback: Boolean
)

object VideoFrameExtractor {

    private const val CUSTOMIZE_SH = """#!/system/bin/sh

ui_print "- Bootanimation auto-path fix (skye)"

SRC="${'$'}MODPATH/system/product/media/bootanimation.zip"
TMP="${'$'}MODPATH/.bootanimation.zip"

if [ ! -f "${'$'}SRC" ]; then
  ui_print "! bootanimation.zip missing from package, abort"
  abort "Broken package"
fi

TARGET=""
for p in \
  /system/media \
  /system/product/media \
  /product/media \
  /system_ext/media \
  /odm/media \
  /vendor/media \
  /system/system/media \
  /system/system/product/media
do
  if [ -f "${'$'}p/bootanimation.zip" ]; then
    TARGET="${'$'}p"
    break
  fi
done

if [ -z "${'$'}TARGET" ]; then
  TARGET="/system/product/media"
  ui_print "- No existing bootanimation detected, defaulting to ${'$'}TARGET"
else
  ui_print "- Existing bootanimation found at ${'$'}TARGET"
fi

REL="${'$'}{TARGET#/}"
case "${'$'}REL" in
  system/system/*) REL="system/${'$'}{REL#system/system/}" ;;
esac

mv -f "${'$'}SRC" "${'$'}TMP"
rm -rf "${'$'}MODPATH/system"
mkdir -p "${'$'}MODPATH/${'$'}REL"
mv -f "${'$'}TMP" "${'$'}MODPATH/${'$'}REL/bootanimation.zip"

ui_print "- Bootanimation will overlay /${'$'}REL/bootanimation.zip"
ui_print "- Done"
"""

    fun buildBootAnimationModule(
        context: Context,
        videoUri: Uri,
        options: NativeBootAnimOptions,
        originalBootAnimZip: File?,
        outModuleZip: File,
        onProgress: (current: Int, total: Int) -> Unit
    ): NativeBootAnimResult {
        val tTotalStart = SystemClock.elapsedRealtime()
        val workerCount = max(1, Runtime.getRuntime().availableProcessors() - 1)
        val pool = Executors.newFixedThreadPool(workerCount)

        var decodeMs = 0L
        var drawMs = 0L
        var encodeMs = 0L
        var usedFallback = false

        val encodeFutures = mutableListOf<Future<ByteArray>>()

        try {
            val codecOutcome = runCatching {
                extractWithMediaCodec(
                    context = context,
                    videoUri = videoUri,
                    options = options,
                    pool = pool,
                    futuresOut = encodeFutures,
                    onProgress = onProgress
                )
            }

            if (codecOutcome.isSuccess) {
                val timings = codecOutcome.getOrThrow()
                decodeMs = timings.first
                drawMs = timings.second
            } else {
                usedFallback = true
                encodeFutures.clear()
                val timings = extractWithRetriever(
                    context = context,
                    videoUri = videoUri,
                    options = options,
                    pool = pool,
                    futuresOut = encodeFutures,
                    onProgress = onProgress
                )
                decodeMs = timings.first
                drawMs = timings.second
            }

            val tEncodeStart = SystemClock.elapsedRealtime()
            val encodedFrames = ArrayList<ByteArray>(encodeFutures.size)
            for (future in encodeFutures) {
                encodedFrames.add(future.get())
            }
            encodeMs = SystemClock.elapsedRealtime() - tEncodeStart

            val tZipStart = SystemClock.elapsedRealtime()
            val tmpDir = File(context.cacheDir, "bootanim_build").apply { mkdirs() }
            val innerZip = File(tmpDir, "bootanimation.zip")
            writeInnerBootAnimationZip(
                innerZip = innerZip,
                options = options,
                frames = encodedFrames,
                originalBootAnimZip = originalBootAnimZip
            )
            encodedFrames.clear()

            writeMagiskModuleZip(
                innerBootAnimZip = innerZip,
                outModuleZip = outModuleZip
            )
            innerZip.delete()
            val zipMs = SystemClock.elapsedRealtime() - tZipStart
            val totalMs = SystemClock.elapsedRealtime() - tTotalStart

            return NativeBootAnimResult(
                outputZip = outModuleZip,
                frameCount = encodeFutures.size,
                decodeMs = decodeMs,
                drawMs = drawMs,
                encodeMs = encodeMs,
                zipMs = zipMs,
                totalMs = totalMs,
                usedFallback = usedFallback
            )
        } finally {
            pool.shutdownNow()
        }
    }

    private fun extractWithMediaCodec(
        context: Context,
        videoUri: Uri,
        options: NativeBootAnimOptions,
        pool: java.util.concurrent.ExecutorService,
        futuresOut: MutableList<Future<ByteArray>>,
        onProgress: (Int, Int) -> Unit
    ): Pair<Long, Long> {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var decodeAccumMs = 0L
        var drawAccumMs = 0L

        try {
            extractor.setDataSource(context, videoUri, null)
            var trackIndex = -1
            var trackFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("video/")) {
                    trackIndex = i
                    trackFormat = fmt
                    break
                }
            }
            if (trackIndex < 0 || trackFormat == null) {
                throw IllegalStateException("Track video tidak ditemukan.")
            }

            extractor.selectTrack(trackIndex)
            val mime = trackFormat.getString(MediaFormat.KEY_MIME)!!
            val durationUs = if (trackFormat.containsKey(MediaFormat.KEY_DURATION)) {
                trackFormat.getLong(MediaFormat.KEY_DURATION)
            } else {
                readDurationFromRetrieverMs(context, videoUri) * 1000L
            }
            val durationSec = durationUs / 1_000_000.0
            val totalFrames = floor(min(options.secs, durationSec) * options.fps).toInt()
            if (totalFrames <= 0) {
                throw IllegalStateException("Durasi video atau FPS menghasilkan 0 frame.")
            }

            val rotation = if (trackFormat.containsKey(MediaFormat.KEY_ROTATION)) {
                trackFormat.getInteger(MediaFormat.KEY_ROTATION)
            } else {
                readRotationFromRetriever(context, videoUri)
            }

            trackFormat.setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
            )

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(trackFormat, null, null, 0)
            codec.start()

            val bufferInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var nextTargetIdx = 0
            var lastProgressEmit = 0L

            val frameStepUs = 1_000_000.0 / options.fps

            while (!outputDone && nextTargetIdx < totalFrames) {
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val inputBuf = codec.getInputBuffer(inIdx)!!
                        val sampleSize = extractor.readSampleData(inputBuf, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(
                                inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputDone = true
                        } else {
                            val pts = extractor.sampleTime
                            codec.queueInputBuffer(inIdx, 0, sampleSize, pts, 0)
                            extractor.advance()
                        }
                    }
                }

                val tDec0 = SystemClock.elapsedRealtime()
                val outIdx = codec.dequeueOutputBuffer(bufferInfo, 10_000)
                decodeAccumMs += SystemClock.elapsedRealtime() - tDec0

                if (outIdx >= 0) {
                    val ptsUs = bufferInfo.presentationTimeUs
                    val isEos = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0

                    val targetPtsUs = (nextTargetIdx * frameStepUs).toLong()
                    if (ptsUs >= targetPtsUs || (isEos && nextTargetIdx < totalFrames)) {
                        val image = codec.getOutputImage(outIdx)
                            ?: throw IllegalStateException("Output Image null (fallback ke retriever)")

                        val tDraw0 = SystemClock.elapsedRealtime()
                        val fittedBitmap = yuvImageToFittedBitmap(
                            image = image,
                            rotationDegrees = rotation,
                            outW = options.width,
                            outH = options.height,
                            fitMode = options.fitMode
                        )
                        drawAccumMs += SystemClock.elapsedRealtime() - tDraw0
                        image.close()
                        codec.releaseOutputBuffer(outIdx, false)

                        // Handle cases where source FPS < target FPS by reusing frame
                        while (nextTargetIdx < totalFrames &&
                            (ptsUs >= (nextTargetIdx * frameStepUs).toLong() || isEos)
                        ) {
                            val bmpRef = fittedBitmap
                            val isLastUser = (nextTargetIdx + 1 >= totalFrames) ||
                                (!isEos && ptsUs < ((nextTargetIdx + 1) * frameStepUs).toLong())
                            val taskBmp = if (isLastUser) bmpRef else bmpRef.copy(Bitmap.Config.ARGB_8888, false)
                            futuresOut.add(pool.submit(Callable {
                                val baos = ByteArrayOutputStream(32768)
                                taskBmp.compress(Bitmap.CompressFormat.JPEG, 85, baos)
                                taskBmp.recycle()
                                baos.toByteArray()
                            }))
                            nextTargetIdx++
                            val now = SystemClock.elapsedRealtime()
                            if (now - lastProgressEmit >= 100L || nextTargetIdx == totalFrames) {
                                lastProgressEmit = now
                                onProgress(nextTargetIdx, totalFrames)
                            }
                        }
                    } else {
                        codec.releaseOutputBuffer(outIdx, false)
                    }

                    if (isEos) outputDone = true
                }
            }

            if (nextTargetIdx == 0) {
                throw IllegalStateException("MediaCodec tidak menghasilkan frame.")
            }
            return decodeAccumMs to drawAccumMs
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    private fun extractWithRetriever(
        context: Context,
        videoUri: Uri,
        options: NativeBootAnimOptions,
        pool: java.util.concurrent.ExecutorService,
        futuresOut: MutableList<Future<ByteArray>>,
        onProgress: (Int, Int) -> Unit
    ): Pair<Long, Long> {
        val mmr = MediaMetadataRetriever()
        var decodeAccumMs = 0L
        var drawAccumMs = 0L
        try {
            mmr.setDataSource(context, videoUri)
            val durMs = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val durationSec = durMs / 1000.0
            val totalFrames = floor(min(options.secs, durationSec) * options.fps).toInt()
            if (totalFrames <= 0) {
                throw IllegalStateException("Durasi video tidak mencukupi.")
            }

            var lastProgressEmit = 0L
            for (i in 0 until totalFrames) {
                val timeUs = ((i.toDouble() / options.fps + 0.001) * 1_000_000.0).toLong()
                val tDec0 = SystemClock.elapsedRealtime()
                val raw = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                    mmr.getScaledFrameAtTime(
                        timeUs,
                        MediaMetadataRetriever.OPTION_CLOSEST,
                        options.width,
                        options.height
                    )
                } else {
                    mmr.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
                }
                decodeAccumMs += SystemClock.elapsedRealtime() - tDec0

                if (raw == null) continue

                val tDraw0 = SystemClock.elapsedRealtime()
                val fitted = fitBitmap(raw, options.width, options.height, options.fitMode)
                if (raw !== fitted) raw.recycle()
                drawAccumMs += SystemClock.elapsedRealtime() - tDraw0

                futuresOut.add(pool.submit(Callable {
                    val baos = ByteArrayOutputStream(32768)
                    fitted.compress(Bitmap.CompressFormat.JPEG, 85, baos)
                    fitted.recycle()
                    baos.toByteArray()
                }))

                val now = SystemClock.elapsedRealtime()
                if (now - lastProgressEmit >= 100L || i + 1 == totalFrames) {
                    lastProgressEmit = now
                    onProgress(i + 1, totalFrames)
                }
            }
            return decodeAccumMs to drawAccumMs
        } finally {
            runCatching { mmr.release() }
        }
    }

    private fun yuvImageToFittedBitmap(
        image: Image,
        rotationDegrees: Int,
        outW: Int,
        outH: Int,
        fitMode: String
    ): Bitmap {
        val srcW = image.width
        val srcH = image.height
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val yBuf: ByteBuffer = yPlane.buffer
        val uBuf: ByteBuffer = uPlane.buffer
        val vBuf: ByteBuffer = vPlane.buffer

        val yArr = ByteArray(yBuf.limit()).also { val d = yBuf.duplicate(); d.position(0); d.get(it) }
        val uArr = ByteArray(uBuf.limit()).also { val d = uBuf.duplicate(); d.position(0); d.get(it) }
        val vArr = ByteArray(vBuf.limit()).also { val d = vBuf.duplicate(); d.position(0); d.get(it) }

        val yRowStride = yPlane.rowStride
        val yPixStride = yPlane.pixelStride
        val uRowStride = uPlane.rowStride
        val uPixStride = uPlane.pixelStride
        val vRowStride = vPlane.rowStride
        val vPixStride = vPlane.pixelStride

        val effW = if (rotationDegrees == 90 || rotationDegrees == 270) srcH else srcW
        val effH = if (rotationDegrees == 90 || rotationDegrees == 270) srcW else srcH

        val scaleX = outW.toFloat() / effW.toFloat()
        val scaleY = outH.toFloat() / effH.toFloat()
        val k = when (fitMode) {
            "contain" -> min(scaleX, scaleY)
            "cover" -> max(scaleX, scaleY)
            else -> 1f
        }

        val drawW = if (fitMode == "stretch") outW.toFloat() else effW * k
        val drawH = if (fitMode == "stretch") outH.toFloat() else effH * k
        val offsetX = (outW - drawW) / 2f
        val offsetY = (outH - drawH) / 2f

        val startX = max(0, floor(offsetX.toDouble()).toInt())
        val endX = min(outW, floor((offsetX + drawW).toDouble()).toInt())
        val startY = max(0, floor(offsetY.toDouble()).toInt())
        val endY = min(outH, floor((offsetY + drawH).toDouble()).toInt())

        val pixels = IntArray(outW * outH)
        pixels.fill(Color.BLACK)

        for (py in startY until endY) {
            val ey = ((py - offsetY) / drawH * effH).toInt().coerceIn(0, effH - 1)
            val rowOff = py * outW
            for (px in startX until endX) {
                val ex = ((px - offsetX) / drawW * effW).toInt().coerceIn(0, effW - 1)
                val sx: Int
                val sy: Int
                when (rotationDegrees) {
                    90 -> {
                        sx = ey
                        sy = srcH - 1 - ex
                    }
                    180 -> {
                        sx = srcW - 1 - ex
                        sy = srcH - 1 - ey
                    }
                    270 -> {
                        sx = srcW - 1 - ey
                        sy = ex
                    }
                    else -> {
                        sx = ex
                        sy = ey
                    }
                }

                val yIdx = sy * yRowStride + sx * yPixStride
                val uIdx = (sy shr 1) * uRowStride + (sx shr 1) * uPixStride
                val vIdx = (sy shr 1) * vRowStride + (sx shr 1) * vPixStride

                val yVal = if (yIdx in yArr.indices) (yArr[yIdx].toInt() and 0xFF) else 16
                val uVal = if (uIdx in uArr.indices) (uArr[uIdx].toInt() and 0xFF) - 128 else 0
                val vVal = if (vIdx in vArr.indices) (vArr[vIdx].toInt() and 0xFF) - 128 else 0

                val y1192 = 1192 * max(0, yVal - 16)
                var r = (y1192 + 1634 * vVal) shr 10
                var g = (y1192 - 833 * vVal - 400 * uVal) shr 10
                var b = (y1192 + 2066 * uVal) shr 10

                if (r < 0) r = 0 else if (r > 255) r = 255
                if (g < 0) g = 0 else if (g > 255) g = 255
                if (b < 0) b = 0 else if (b > 255) b = 255

                pixels[rowOff + px] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }

        return Bitmap.createBitmap(pixels, outW, outH, Bitmap.Config.ARGB_8888)
    }

    private fun fitBitmap(src: Bitmap, outW: Int, outH: Int, fitMode: String): Bitmap {
        val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.BLACK)
        val sw = src.width.toFloat()
        val sh = src.height.toFloat()
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)

        if (fitMode == "stretch") {
            canvas.drawBitmap(src, null, RectF(0f, 0f, outW.toFloat(), outH.toFloat()), paint)
            return out
        }
        val k = if (fitMode == "contain") {
            min(outW / sw, outH / sh)
        } else {
            max(outW / sw, outH / sh)
        }
        val dw = sw * k
        val dh = sh * k
        val left = (outW - dw) / 2f
        val top = (outH - dh) / 2f
        val matrix = Matrix().apply {
            postScale(k, k)
            postTranslate(left, top)
        }
        canvas.drawBitmap(src, matrix, paint)
        return out
    }

    private fun writeInnerBootAnimationZip(
        innerZip: File,
        options: NativeBootAnimOptions,
        frames: List<ByteArray>,
        originalBootAnimZip: File?
    ) {
        val partPrefix = "${options.partName}/"
        ZipOutputStream(BufferedOutputStream(FileOutputStream(innerZip), 65536)).use { zos ->
            if (originalBootAnimZip != null && originalBootAnimZip.exists()) {
                ZipInputStream(FileInputStream(originalBootAnimZip)).use { zis ->
                    val buf = ByteArray(65536)
                    while (true) {
                        val entry = zis.nextEntry ?: break
                        val name = entry.name
                        if (!entry.isDirectory && !name.startsWith(partPrefix)) {
                            val baos = ByteArrayOutputStream()
                            while (true) {
                                val n = zis.read(buf)
                                if (n <= 0) break
                                baos.write(buf, 0, n)
                            }
                            writeStoredEntry(zos, name, baos.toByteArray())
                        }
                        zis.closeEntry()
                    }
                }
            } else {
                val desc = "${options.width} ${options.height} ${options.fps}\np 0 0 ${options.partName}\n"
                    .toByteArray(Charsets.UTF_8)
                writeStoredEntry(zos, "desc.txt", desc)
            }

            frames.forEachIndexed { idx, jpgBytes ->
                val frameName = "$partPrefix%04d.jpg".format(idx)
                writeStoredEntry(zos, frameName, jpgBytes)
            }
        }
    }

    private fun writeMagiskModuleZip(
        innerBootAnimZip: File,
        outModuleZip: File
    ) {
        outModuleZip.parentFile?.mkdirs()
        val moduleProp = (
            "id=customboota\n" +
                "name=Custom Boot Animation\n" +
                "version=1\n" +
                "versionCode=1\n" +
                "author=logotool\n" +
                "description=Custom bootanimation\n"
            ).toByteArray(Charsets.UTF_8)
        val customizeSh = CUSTOMIZE_SH.toByteArray(Charsets.UTF_8)
        val magiskMarker = "#MAGISK\n".toByteArray(Charsets.UTF_8)
        val bootAnimBytes = innerBootAnimZip.readBytes()

        ZipOutputStream(BufferedOutputStream(FileOutputStream(outModuleZip), 65536)).use { zos ->
            writeStoredEntry(zos, "module.prop", moduleProp)
            writeStoredEntry(zos, "customize.sh", customizeSh)
            writeStoredEntry(zos, "system/media/bootanimation.zip", bootAnimBytes)
            writeStoredEntry(zos, "system/product/media/bootanimation.zip", bootAnimBytes)
            writeStoredEntry(zos, "META-INF/com/google/android/update-binary", magiskMarker)
            writeStoredEntry(zos, "META-INF/com/google/android/updater-script", magiskMarker)
        }
    }

    private fun writeStoredEntry(zos: ZipOutputStream, name: String, data: ByteArray) {
        val crc = CRC32().apply { update(data) }
        val entry = ZipEntry(name).apply {
            method = ZipEntry.STORED
            size = data.size.toLong()
            compressedSize = data.size.toLong()
            this.crc = crc.value
        }
        zos.putNextEntry(entry)
        zos.write(data)
        zos.closeEntry()
    }

    private fun readDurationFromRetrieverMs(context: Context, uri: Uri): Long {
        val mmr = MediaMetadataRetriever()
        return try {
            mmr.setDataSource(context, uri)
            mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } finally {
            runCatching { mmr.release() }
        }
    }

    private fun readRotationFromRetriever(context: Context, uri: Uri): Int {
        val mmr = MediaMetadataRetriever()
        return try {
            mmr.setDataSource(context, uri)
            mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        } finally {
            runCatching { mmr.release() }
        }
    }
}
