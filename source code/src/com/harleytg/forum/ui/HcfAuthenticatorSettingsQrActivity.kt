@file:Suppress("DEPRECATION")

package com.harleytg.forum.dev

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.Camera
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.InvertedLuminanceSource
import com.google.zxing.LuminanceSource
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import java.util.Collections
import java.util.EnumMap
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * HCF_AUTHENTICATOR_NATIVE_QR_SCANNER_V3_SYSTEM_UI
 *
 * Native camera/image QR importer used by HCF Authenticator.
 */
class HcfAuthenticatorSettingsQrActivity : Activity(), SurfaceHolder.Callback {
    private val main = Handler(Looper.getMainLooper())
    private var preview: SurfaceView? = null
    private var previewHolder: SurfaceHolder? = null
    private var status: TextView? = null
    private var retryCamera: Button? = null
    private var camera: Camera? = null
    private var cameraId = -1
    private var surfaceReady = false
    private var decodeBusy = false
    private var resultHandled = false
    private var decodeThread: HandlerThread? = null
    private var decodeHandler: Handler? = null

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        window.statusBarColor = BG
        window.navigationBarColor = BG

        decodeThread = HandlerThread("HcfQrDecode").also {
            it.start()
            decodeHandler = Handler(it.looper)
        }

        buildUi()
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            setStatus(
                "Camera permission is needed for live scanning. You can still import a QR image.",
                false
            )
            requestPermissions(arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA)
        }
    }

    override fun onResume() {
        super.onResume()
        if (
            surfaceReady &&
            checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED &&
            camera == null &&
            !resultHandled
        ) {
            startCamera()
        }
    }

    override fun onPause() {
        stopCamera()
        super.onPause()
    }

    override fun onDestroy() {
        stopCamera()
        decodeThread?.quitSafely()
        decodeThread = null
        decodeHandler = null
        super.onDestroy()
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceReady = true
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        val active = camera ?: return
        if (!surfaceReady) return
        try {
            active.stopPreview()
            active.setPreviewDisplay(holder)
            active.startPreview()
            requestNextFrame()
        } catch (_: Throwable) {
            setStatus(
                "Camera preview could not restart. Tap Restart camera or import a QR image.",
                false
            )
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceReady = false
        stopCamera()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_CAMERA) return
        val allowed =
            grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
        if (allowed) {
            setStatus("Camera ready. Point it at the QR code in forum User Settings.", true)
            if (surfaceReady) startCamera()
        } else {
            setStatus(
                "Camera access is off. Import the QR image instead, or tap Restart camera to grant access.",
                false
            )
        }
    }

    @Deprecated("Deprecated in Android")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val image = data?.data
        if (requestCode != REQUEST_IMAGE || resultCode != RESULT_OK || image == null) return
        setStatus("Reading QR image on this device…", true)
        val worker = decodeHandler ?: return
        worker.post {
            val value = decodeImage(image)
            main.post {
                if (value != null) {
                    handleDecodedValue(value)
                } else {
                    setStatus(
                        "No TOTP authenticator QR code was found. Try a clearer image or use the Setup key.",
                        false
                    )
                }
            }
        }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            setPadding(dp(12), dp(9), dp(12), dp(12))
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        header.addView(iconButton(R.drawable.fa_arrow_left, "Back").apply {
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(58), dp(58)))

        header.addView(ImageView(this).apply {
            setImageResource(R.drawable.htg_app_logo)
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "Harley's Clan Forum logo"
        }, LinearLayout.LayoutParams(dp(52), dp(52)).apply { leftMargin = dp(10) })

        val labels = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        labels.addView(text("Scan 2FA QR", 20f, TEXT).apply {
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        })
        labels.addView(text("HCF Authenticator Setup • Nearata", 10f, CYAN).apply {
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(3) })
        header.addView(labels, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(12) })
        root.addView(header, LinearLayout.LayoutParams(-1, -2))

        val scannerCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = roundRect(PANEL, BORDER, 15)
        }
        root.addView(scannerCard, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(13) })

        scannerCard.addView(text("Camera scanner", 11f, CYAN).apply {
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        })
        scannerCard.addView(
            text(
                "Point the camera at the QR code shown in forum User Settings → Two-Factor Authentication.",
                10f,
                MUTED
            ).apply { setLineSpacing(0f, 1.08f) },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) }
        )

        val stageShell = FrameLayout(this).apply {
            background = roundRect(Color.rgb(7, 11, 14), BORDER, 13)
            setPadding(dp(2), dp(2), dp(2), dp(2))
        }
        scannerCard.addView(stageShell, LinearLayout.LayoutParams(-1, 0, 1f).apply {
            topMargin = dp(10)
        })

        val stage = FrameLayout(this).apply { setBackgroundColor(Color.rgb(7, 11, 14)) }
        preview = SurfaceView(this).also { surface ->
            previewHolder = surface.holder.also { it.addCallback(this) }
            stage.addView(surface, FrameLayout.LayoutParams(-1, -1))
        }
        stage.addView(ScannerOverlay(this), FrameLayout.LayoutParams(-1, -1))
        stageShell.addView(stage, FrameLayout.LayoutParams(-1, -1))

        status = text("Preparing native QR scanner…", 10f, MUTED).apply {
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.06f)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = roundRect(SURFACE, BORDER, 10)
        }
        scannerCard.addView(status, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(9) })

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(11), dp(12), dp(12))
            background = roundRect(PANEL, BORDER, 15)
        }
        root.addView(controls, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(11) })
        controls.addView(text("Scan options", 11f, CYAN).apply {
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        })

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        controls.addView(actions, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(9) })

        retryCamera = button("Restart camera", true).apply {
            setOnClickListener { retryCamera() }
        }
        actions.addView(retryCamera, LinearLayout.LayoutParams(0, dp(48), 1f))

        actions.addView(button("Import QR image", false).apply {
            setOnClickListener { chooseImage() }
        }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { leftMargin = dp(9) })

        controls.addView(button("Use Setup key instead", false).apply {
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(9) })

        controls.addView(
            text(
                "QR data is decoded locally on this device and saved only to the encrypted HCF Authenticator vault.",
                9f,
                MUTED
            ).apply {
                gravity = Gravity.CENTER
                setLineSpacing(0f, 1.08f)
            },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(9) }
        )
        setContentView(root)
    }

    private fun retryCamera() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA)
            return
        }
        stopCamera()
        if (surfaceReady) startCamera()
    }

    private fun chooseImage() {
        try {
            startActivityForResult(
                Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "image/*"
                },
                REQUEST_IMAGE
            )
        } catch (_: Throwable) {
            Toast.makeText(this, "No image picker is available.", Toast.LENGTH_LONG).show()
        }
    }

    private fun startCamera() {
        if (!surfaceReady || resultHandled || camera != null) return
        try {
            cameraId = findBackCamera()
            if (cameraId < 0) throw IllegalStateException("No rear camera")
            val opened = Camera.open(cameraId)
            val params = opened.parameters
            params.previewFormat = ImageFormat.NV21
            choosePreviewSize(params.supportedPreviewSizes)?.let { params.setPreviewSize(it.width, it.height) }

            params.supportedFocusModes?.let { modes ->
                params.focusMode = when {
                    modes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE) ->
                        Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE
                    modes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO) ->
                        Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO
                    modes.contains(Camera.Parameters.FOCUS_MODE_AUTO) ->
                        Camera.Parameters.FOCUS_MODE_AUTO
                    else -> params.focusMode
                }
            }

            opened.parameters = params
            opened.setDisplayOrientation(cameraDisplayOrientation(cameraId))
            opened.setPreviewDisplay(previewHolder)
            opened.startPreview()
            camera = opened
            setStatus("Scanning… hold the Nearata QR code inside the frame.", true)
            requestNextFrame()
        } catch (_: Throwable) {
            stopCamera()
            setStatus(
                "The camera could not start. Import a QR image or tap Restart camera.",
                false
            )
        }
    }

    private fun stopCamera() {
        val active = camera
        camera = null
        decodeBusy = false
        if (active == null) return
        try { active.setOneShotPreviewCallback(null) } catch (_: Throwable) {}
        try { active.setPreviewCallback(null) } catch (_: Throwable) {}
        try { active.stopPreview() } catch (_: Throwable) {}
        try { active.release() } catch (_: Throwable) {}
    }

    private fun requestNextFrame() {
        val active = camera ?: return
        val worker = decodeHandler ?: return
        if (decodeBusy || resultHandled) return
        try {
            active.setOneShotPreviewCallback { data, sourceCamera ->
                if (data == null || camera == null || resultHandled || decodeHandler == null) return@setOneShotPreviewCallback
                val size = try {
                    sourceCamera.parameters.previewSize
                } catch (_: Throwable) {
                    scheduleNextFrame()
                    return@setOneShotPreviewCallback
                } ?: run {
                    scheduleNextFrame()
                    return@setOneShotPreviewCallback
                }

                if (size.width <= 0 || size.height <= 0) {
                    scheduleNextFrame()
                    return@setOneShotPreviewCallback
                }
                val yLength = size.width * size.height
                if (data.size < yLength) {
                    scheduleNextFrame()
                    return@setOneShotPreviewCallback
                }

                val luminance = data.copyOf(yLength)
                val width = size.width
                val height = size.height
                decodeBusy = true
                worker.post {
                    val value = decodeCameraFrame(luminance, width, height)
                    main.post {
                        decodeBusy = false
                        if (value != null) handleDecodedValue(value) else scheduleNextFrame()
                    }
                }
            }
        } catch (_: Throwable) {
            decodeBusy = false
            setStatus(
                "Camera frame capture failed. Tap Restart camera or import a QR image.",
                false
            )
        }
    }

    private fun scheduleNextFrame() {
        if (resultHandled || camera == null) return
        main.postDelayed(::requestNextFrame, 90L)
    }

    private fun decodeCameraFrame(yPlane: ByteArray, width: Int, height: Int): String? {
        var frame = LumaFrame(yPlane, width, height)
        repeat(4) {
            val value = decodeLuma(frame.data, frame.width, frame.height)
            if (isTotp(value)) return value
            frame = rotate90(frame)
        }
        return null
    }

    private fun decodeLuma(data: ByteArray?, width: Int, height: Int): String? {
        if (data == null || width <= 0 || height <= 0 || data.size < width * height) return null
        return try {
            val source: LuminanceSource =
                PlanarYUVLuminanceSource(data, width, height, 0, 0, width, height, false)
            decodeSource(source) ?: decodeSource(InvertedLuminanceSource(source))
        } catch (_: Throwable) {
            null
        }
    }

    private fun decodeImage(uri: Uri): String? {
        var bitmap: Bitmap? = null
        var current: Bitmap? = null
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            } ?: return null

            var sample = 1
            val largest = max(bounds.outWidth, bounds.outHeight)
            while (largest / sample > 2048) sample *= 2

            val options = BitmapFactory.Options().apply {
                inSampleSize = max(1, sample)
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            bitmap = contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, options)
            } ?: return null

            current = bitmap
            repeat(4) { rotation ->
                val frame = current ?: return@repeat
                val value = decodeBitmap(frame)
                if (isTotp(value)) return value
                if (rotation < 3) {
                    val matrix = Matrix().apply { postRotate(90f) }
                    val next = Bitmap.createBitmap(
                        frame, 0, 0, frame.width, frame.height, matrix, true
                    )
                    if (frame !== bitmap && frame !== next && !frame.isRecycled) frame.recycle()
                    current = next
                }
            }
            if (current !== bitmap && current?.isRecycled == false) current?.recycle()
        } catch (_: Throwable) {
            return null
        } finally {
            if (bitmap?.isRecycled == false) bitmap?.recycle()
        }
        return null
    }

    private fun decodeBitmap(bitmap: Bitmap?): String? {
        if (bitmap == null || bitmap.isRecycled) return null
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0) return null
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val source: LuminanceSource = RGBLuminanceSource(width, height, pixels)
        return decodeSource(source) ?: decodeSource(InvertedLuminanceSource(source))
    }

    private fun decodeSource(source: LuminanceSource): String? {
        val reader = QRCodeReader()
        return try {
            reader.decode(BinaryBitmap(HybridBinarizer(source)), QR_HINTS)?.text
        } catch (_: Throwable) {
            null
        } finally {
            try { reader.reset() } catch (_: Throwable) {}
        }
    }

    private fun handleDecodedValue(value: String) {
        if (resultHandled) return
        if (!isTotp(value)) {
            setStatus("A QR code was found, but it is not a TOTP authenticator setup.", false)
            scheduleNextFrame()
            return
        }

        resultHandled = true
        stopCamera()
        setStatus("Authenticator QR detected. Saving securely…", true)
        try {
            val config = HcfAuthenticator.Config.fromOtpAuth(Uri.parse(value.trim()))
            HcfAuthenticator.Vault.save(this, config)
            Toast.makeText(this, "QR code saved to HCF Authenticator.", Toast.LENGTH_SHORT).show()
            setResult(RESULT_OK)
            finish()
        } catch (_: Throwable) {
            resultHandled = false
            setStatus(
                "The QR code was read, but HCF could not save that authenticator setup. Use the Setup key instead.",
                false
            )
            if (
                surfaceReady &&
                checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
            ) {
                startCamera()
            }
        }
    }

    private fun isTotp(value: String?): Boolean =
        value?.trim()?.lowercase(Locale.US)?.startsWith("otpauth://totp/") == true

    private fun findBackCamera(): Int {
        val count = Camera.getNumberOfCameras()
        val info = Camera.CameraInfo()
        for (i in 0 until count) {
            Camera.getCameraInfo(i, info)
            if (info.facing == Camera.CameraInfo.CAMERA_FACING_BACK) return i
        }
        return if (count > 0) 0 else -1
    }

    private fun choosePreviewSize(sizes: List<Camera.Size>?): Camera.Size? {
        if (sizes.isNullOrEmpty()) return null
        var best = sizes[0]
        val targetArea = 1280L * 720L
        var bestDelta = Long.MAX_VALUE
        for (size in sizes) {
            if (size.width <= 0 || size.height <= 0) continue
            val area = size.width.toLong() * size.height
            if (area > 1920L * 1080L) continue
            val delta = abs(area - targetArea)
            if (delta < bestDelta) {
                best = size
                bestDelta = delta
            }
        }
        return best
    }

    private fun cameraDisplayOrientation(id: Int): Int {
        val info = Camera.CameraInfo()
        Camera.getCameraInfo(id, info)
        val degrees = when (windowManager.defaultDisplay.rotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
            val result = (info.orientation + degrees) % 360
            return (360 - result) % 360
        }
        return (info.orientation - degrees + 360) % 360
    }

    private fun setStatus(message: String, positive: Boolean) {
        status?.apply {
            text = message
            setTextColor(if (positive) MUTED else ERROR)
            background = roundRect(
                if (positive) SURFACE else Color.rgb(35, 19, 23),
                if (positive) BORDER else Color.rgb(104, 45, 53),
                10
            )
        }
    }

    private fun text(value: String, sp: Float, color: Int): TextView =
        TextView(this).apply {
            text = value
            setTextColor(color)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        }

    private fun iconButton(drawable: Int, description: String): ImageButton =
        ImageButton(this).apply {
            setImageResource(drawable)
            imageTintList = ColorStateList.valueOf(CYAN)
            contentDescription = description
            scaleType = ImageView.ScaleType.CENTER
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = roundRect(SURFACE, BORDER, 15)
            stateListAnimator = null
        }

    private fun button(value: String, primary: Boolean): Button =
        Button(this).apply {
            isAllCaps = false
            text = value
            setTextColor(if (primary) Color.BLACK else CYAN)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            stateListAnimator = null
            setPadding(dp(10), 0, dp(10), 0)
            background = roundRect(if (primary) CYAN else SURFACE, if (primary) CYAN else BORDER, 12)
        }

    private fun roundRect(fill: Int, stroke: Int, radiusDp: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dp(radiusDp).toFloat()
            setStroke(dp(1), stroke)
        }

    private fun dp(value: Int): Int =
        Math.round(value * resources.displayMetrics.density)

    private data class LumaFrame(val data: ByteArray, val width: Int, val height: Int)

    private class ScannerOverlay(context: Activity) : View(context) {
        private val shade = Paint(Paint.ANTI_ALIAS_FLAG)
        private val frame = Paint(Paint.ANTI_ALIAS_FLAG)
        private val scan = Paint(Paint.ANTI_ALIAS_FLAG)
        private val density = context.resources.displayMetrics.density

        init {
            shade.color = Color.argb(88, 0, 0, 0)
            frame.color = CYAN
            frame.style = Paint.Style.STROKE
            frame.strokeWidth = 2f * density
            scan.color = Color.argb(205, 0, 184, 240)
            scan.strokeWidth = 2f * density
            setLayerType(LAYER_TYPE_SOFTWARE, null)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val size = min(width * 0.72f, 320f * density)
            val left = (width - size) / 2f
            val top = (height - size) / 2f
            val box = RectF(left, top, left + size, top + size)
            canvas.drawRect(0f, 0f, width.toFloat(), top, shade)
            canvas.drawRect(0f, top + size, width.toFloat(), height.toFloat(), shade)
            canvas.drawRect(0f, top, left, top + size, shade)
            canvas.drawRect(left + size, top, width.toFloat(), top + size, shade)
            canvas.drawRoundRect(box, 22f * density, 22f * density, frame)

            val phase = (SystemClock.uptimeMillis() % 1800L) / 1800f
            val lineY = top + size * phase
            canvas.drawLine(
                left + 14f * density,
                lineY,
                left + size - 14f * density,
                lineY,
                scan
            )
            postInvalidateDelayed(16L)
        }
    }

    companion object {
        private const val REQUEST_CAMERA = 8341
        private const val REQUEST_IMAGE = 8342
        private val BG = Color.rgb(8, 13, 17)
        private val PANEL = Color.rgb(18, 28, 35)
        private val SURFACE = Color.rgb(14, 22, 28)
        private val BORDER = Color.rgb(41, 64, 75)
        private val CYAN = Color.rgb(0, 184, 240)
        private val TEXT = Color.rgb(239, 247, 250)
        private val MUTED = Color.rgb(155, 174, 183)
        private val ERROR = Color.rgb(255, 77, 87)

        private val QR_HINTS: Map<DecodeHintType, Any> =
            Collections.unmodifiableMap(
                EnumMap<DecodeHintType, Any>(DecodeHintType::class.java).apply {
                    put(DecodeHintType.POSSIBLE_FORMATS, Collections.singletonList(BarcodeFormat.QR_CODE))
                    put(DecodeHintType.TRY_HARDER, true)
                    put(DecodeHintType.CHARACTER_SET, "UTF-8")
                }
            )

        private fun rotate90(frame: LumaFrame): LumaFrame {
            val output = ByteArray(frame.width * frame.height)
            for (y in 0 until frame.height) {
                val row = y * frame.width
                for (x in 0 until frame.width) {
                    output[x * frame.height + (frame.height - 1 - y)] = frame.data[row + x]
                }
            }
            return LumaFrame(output, frame.height, frame.width)
        }
    }
}
