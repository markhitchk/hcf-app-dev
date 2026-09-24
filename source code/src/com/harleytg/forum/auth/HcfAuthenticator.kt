package com.harleytg.forum.dev

import android.Manifest
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.text.InputType
import android.util.Base64
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.util.Arrays
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Native offline RFC 6238 authenticator for Harley's Clan Forum. */
object HcfAuthenticator {
    const val OPEN_URI: String = "hcf-auth://open"

    private val BG = Color.rgb(13, 16, 20)
    private val PANEL = Color.rgb(17, 27, 34)
    private val PANEL_DARK = Color.rgb(12, 21, 27)
    private val BORDER = Color.rgb(41, 64, 75)
    private val CYAN = Color.rgb(0, 184, 240)
    private val TEXT = Color.rgb(239, 247, 250)
    private val MUTED = Color.rgb(155, 174, 183)
    private val GOOD = Color.rgb(85, 225, 59)
    private val DANGER = Color.rgb(255, 77, 87)

    class Activity : android.app.Activity() {
        private val handler = Handler(Looper.getMainLooper())
        private var config: Config? = null
        private var lastCode = ""
        private lateinit var status: TextView
        private lateinit var account: TextView
        private lateinit var code: TextView
        private lateinit var countdown: TextView
        private lateinit var progress: ProgressBar
        private lateinit var accountInput: EditText
        private lateinit var secretInput: EditText

        private val ticker = object : Runnable {
            override fun run() {
                renderCode()
                handler.postDelayed(this, 250L)
            }
        }

        override fun onCreate(state: Bundle?) {
            super.onCreate(state)
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            window.statusBarColor = BG
            window.navigationBarColor = BG
            buildUi()
            reload()
            handleIntent(intent)
        }

        override fun onNewIntent(intent: Intent?) {
            super.onNewIntent(intent)
            setIntent(intent)
            handleIntent(intent)
        }

        override fun onResume() {
            super.onResume()
            handler.removeCallbacks(ticker)
            handler.post(ticker)
        }

        override fun onPause() {
            handler.removeCallbacks(ticker)
            super.onPause()
        }

        @Deprecated("Deprecated in Android")
        override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
            super.onActivityResult(requestCode, resultCode, data)
            if (requestCode == REQUEST_QR_SCANNER && resultCode == RESULT_OK && data != null) {
                var raw = data.getStringExtra(QrScannerActivity.EXTRA_QR_VALUE)
                if (raw.isNullOrBlank() && data.data != null) raw = data.data.toString()
                importOtpAuth(raw, "QR code")
            }
        }

        private fun buildUi() {
            val scroll = ScrollView(this).apply {
                isFillViewport = true
                setBackgroundColor(BG)
            }
            val root = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(18), dp(12), dp(18), dp(28))
            }
            scroll.addView(root, ScrollView.LayoutParams(-1, -2))

            val header = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            header.addView(button("‹", false).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
                setOnClickListener { finish() }
            }, LinearLayout.LayoutParams(dp(48), dp(44)))

            val labels = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            labels.addView(text("HCF Authenticator", 21f, TEXT).apply {
                setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            })
            labels.addView(text("Two-factor authentication • Offline code generator", 10f, MUTED))
            header.addView(labels, LinearLayout.LayoutParams(0, -2, 1f).apply {
                leftMargin = dp(10)
            })
            root.addView(header)

            val local = panel()
            local.addView(section("LOCAL & ENCRYPTED"))
            local.addView(text(
                "Codes are generated on this device from the encrypted setup key and current time. Internet is not required after setup.",
                11f, MUTED
            ))
            local.addView(text("Screenshots and screen recording are blocked here.", 10f, CYAN).apply {
                setPadding(0, dp(7), 0, 0)
            })
            root.addView(local, panelLp())

            val statePanel = panel()
            statePanel.addView(section("AUTHENTICATOR STATUS"))
            status = text("Not configured", 16f, DANGER).apply {
                setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            }
            statePanel.addView(status)
            account = text("No forum account enrolled", 11f, MUTED).apply {
                setPadding(0, dp(4), 0, 0)
            }
            statePanel.addView(account)
            root.addView(statePanel, panelLp())

            val current = panel()
            current.addView(section("CURRENT ACCESS CODE"))
            code = text("--- ---", 38f, TEXT).apply {
                setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
                gravity = Gravity.CENTER
                letterSpacing = 0.08f
                setPadding(0, dp(8), 0, dp(4))
            }
            current.addView(code, LinearLayout.LayoutParams(-1, -2))
            countdown = text("Configure the authenticator to generate a code.", 11f, MUTED).apply {
                gravity = Gravity.CENTER
            }
            current.addView(countdown, LinearLayout.LayoutParams(-1, -2))
            progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 30
            }
            current.addView(progress, LinearLayout.LayoutParams(-1, dp(5)).apply {
                topMargin = dp(10)
            })
            current.addView(button("Copy current code", true).apply {
                setOnClickListener { copyCode() }
            }, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(12) })
            root.addView(current, panelLp())

            val setup = panel()
            setup.addView(section("SET UP FROM THE FORUM"))
            setup.addView(text(
                "Scan the forum's Two-Factor Authentication QR code, import a QR screenshot, paste an otpauth:// setup link, or enter the Base32 setup key manually.",
                11f, MUTED
            ))
            setup.addView(button("Scan or import QR code", true).apply {
                setOnClickListener {
                    startActivityForResult(
                        Intent(this@Activity, QrScannerActivity::class.java),
                        REQUEST_QR_SCANNER
                    )
                }
            }, LinearLayout.LayoutParams(-1, dp(50)).apply { topMargin = dp(12) })
            setup.addView(button("Paste authenticator setup link", false).apply {
                setOnClickListener { pasteSetupLink() }
            }, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(8) })
            setup.addView(text("Manual setup key", 10f, CYAN).apply {
                setTypeface(Typeface.DEFAULT, Typeface.BOLD)
                setPadding(0, dp(14), 0, 0)
            })

            accountInput = input("Forum account name (optional)").apply {
                inputType = InputType.TYPE_CLASS_TEXT
            }
            setup.addView(accountInput, LinearLayout.LayoutParams(-1, dp(50)).apply {
                topMargin = dp(9)
            })
            secretInput = input("Base32 setup key").apply {
                inputType =
                    InputType.TYPE_CLASS_TEXT or
                    InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or
                    InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            }
            setup.addView(secretInput, LinearLayout.LayoutParams(-1, dp(50)).apply {
                topMargin = dp(8)
            })
            setup.addView(button("Save setup key", true).apply {
                setOnClickListener { saveManual() }
            }, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(10) })
            root.addView(setup, panelLp())

            val manage = panel()
            manage.addView(section("MANAGE"))
            manage.addView(button("Open Harley's Clan Forum", false).apply {
                setOnClickListener { openForum() }
            }, LinearLayout.LayoutParams(-1, dp(48)))
            manage.addView(button("Remove authenticator from this device", false).apply {
                setTextColor(DANGER)
                setOnClickListener { removeAuthenticator() }
            }, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(8) })
            manage.addView(text(
                "Removing the key here does not disable 2FA on the website. Keep your forum recovery codes somewhere safe.",
                10f, MUTED
            ).apply { setPadding(0, dp(10), 0, 0) })
            root.addView(manage, panelLp())

            root.addView(text(
                "RFC 6238 TOTP • Android Keystore • QR setup stays on-device • No cloud sync",
                9f, MUTED
            ).apply { gravity = Gravity.CENTER })
            setContentView(scroll)
        }

        private fun reload() {
            try {
                config = Vault.load(this)
            } catch (_: Throwable) {
                Vault.clear(this)
                config = null
                Toast.makeText(
                    this,
                    "Authenticator storage could not be unlocked. Set it up again.",
                    Toast.LENGTH_LONG
                ).show()
            }
            renderState()
        }

        private fun renderState() {
            val ready = config?.secret?.isNotEmpty() == true
            status.text = if (ready) "Configured" else "Not configured"
            status.setTextColor(if (ready) GOOD else DANGER)
            if (ready) {
                val active = config!!
                account.text = active.displayLabel() + " • " + active.digits + " digits • " +
                    active.period + " sec"
            } else {
                account.text = "No forum account enrolled"
                code.text = "--- ---"
                countdown.text = "Configure the authenticator to generate a code."
                progress.max = 30
                progress.progress = 0
                lastCode = ""
            }
        }

        private fun renderCode() {
            val active = config ?: return
            try {
                val now = System.currentTimeMillis() / 1000L
                lastCode = Totp.generate(active, now)
                code.text = formatCode(lastCode)
                val elapsed = (now % active.period).toInt()
                val remaining = active.period - elapsed
                progress.max = active.period
                progress.progress = elapsed
                countdown.text = "New code in $remaining second" +
                    if (remaining == 1) " • works offline" else "s • works offline"
            } catch (_: Throwable) {
                lastCode = ""
                code.text = "--- ---"
                countdown.text = "Unable to generate code. Check the setup key and device time."
            }
        }

        private fun handleIntent(intent: Intent?) {
            val uri = intent?.data ?: return
            if (!"otpauth".equals(uri.scheme, true)) return
            importOtpAuth(uri.toString(), "setup link")
        }

        private fun pasteSetupLink() {
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as? ClipboardManager
            if (clipboard == null || !clipboard.hasPrimaryClip()) {
                Toast.makeText(this, "Clipboard is empty.", Toast.LENGTH_SHORT).show()
                return
            }
            val clip: ClipData? = clipboard.primaryClip
            val value = if (clip == null || clip.itemCount == 0) null
                else clip.getItemAt(0).coerceToText(this)
            if (value.isNullOrBlank()) {
                Toast.makeText(
                    this,
                    "Clipboard does not contain an authenticator setup link.",
                    Toast.LENGTH_LONG
                ).show()
                return
            }
            importOtpAuth(value.toString(), "clipboard")
        }

        private fun importOtpAuth(raw: String?, source: String) {
            if (raw.isNullOrBlank()) return
            try {
                val incoming = Config.fromOtpAuth(Uri.parse(raw.trim()))
                confirmSave(incoming, if (config == null) "Add" else "Replace", source)
            } catch (_: Throwable) {
                Toast.makeText(
                    this,
                    "The $source is not a supported TOTP authenticator setup.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

        private fun saveManual() {
            val raw = secretInput.text.toString().trim()
            if (raw.isEmpty()) {
                secretInput.error = "Enter the setup key from the forum"
                return
            }
            try {
                val incoming = Config.manual(raw, accountInput.text.toString().trim())
                Base32.decode(incoming.secret)
                if (config == null) save(incoming)
                else confirmSave(incoming, "Replace", "manual setup key")
            } catch (_: Throwable) {
                secretInput.error = "Invalid Base32 setup key"
            }
        }

        private fun confirmSave(incoming: Config, action: String, source: String) {
            AlertDialog.Builder(this)
                .setTitle("$action HCF authenticator?")
                .setMessage(
                    "Account: " + incoming.displayLabel() +
                        "\nSource: " + source +
                        "\n\nThe setup secret will be encrypted with Android Keystore and stored only on this device."
                )
                .setNegativeButton("Cancel", null)
                .setPositiveButton(action) { _, _ -> save(incoming) }
                .show()
        }

        private fun save(incoming: Config) {
            try {
                Vault.save(this, incoming)
                config = incoming
                secretInput.setText("")
                renderState()
                renderCode()
                Toast.makeText(this, "HCF Authenticator configured.", Toast.LENGTH_SHORT).show()
            } catch (_: Throwable) {
                Toast.makeText(
                    this,
                    "Could not securely save this authenticator.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

        private fun copyCode() {
            if (lastCode.isEmpty()) {
                Toast.makeText(
                    this,
                    "No authentication code is available yet.",
                    Toast.LENGTH_SHORT
                ).show()
                return
            }
            (getSystemService(CLIPBOARD_SERVICE) as? ClipboardManager)?.let {
                it.setPrimaryClip(ClipData.newPlainText("HCF authentication code", lastCode))
                Toast.makeText(this, "Authentication code copied.", Toast.LENGTH_SHORT).show()
            }
        }

        private fun removeAuthenticator() {
            if (config == null) {
                Toast.makeText(
                    this,
                    "No authenticator is configured on this device.",
                    Toast.LENGTH_SHORT
                ).show()
                return
            }
            AlertDialog.Builder(this)
                .setTitle("Remove HCF Authenticator?")
                .setMessage(
                    "This deletes the encrypted setup key from this device. It does not disable two-factor authentication on the website."
                )
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Remove") { _, _ ->
                    Vault.clear(this)
                    config = null
                    renderState()
                }
                .show()
        }

        private fun openForum() {
            startActivity(Intent(this, HcfForum.MainActivity::class.java).apply {
                data = Uri.parse("https://forum.harleytg.com/")
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            })
        }

        private fun input(hint: String): EditText = EditText(this).apply {
            this.hint = hint
            setHintTextColor(MUTED)
            setTextColor(TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            isSingleLine = true
            setPadding(dp(13), 0, dp(13), 0)
            background = rounded(PANEL_DARK, BORDER, 12, 1)
        }

        private fun panel(): LinearLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(15), dp(14), dp(15), dp(14))
            background = rounded(PANEL, BORDER, 16, 1)
        }

        private fun panelLp() = LinearLayout.LayoutParams(-1, -2).apply {
            bottomMargin = dp(10)
        }

        private fun section(value: String): TextView = text(value, 10f, CYAN).apply {
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            setPadding(0, 0, 0, dp(8))
        }

        private fun text(value: String, sp: Float, color: Int): TextView =
            TextView(this).apply {
                text = value
                setTextColor(color)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
            }

        private fun button(value: String, primary: Boolean): Button =
            Button(this).apply {
                isAllCaps = false
                text = value
                setTextColor(if (primary) Color.BLACK else TEXT)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTypeface(Typeface.DEFAULT, Typeface.BOLD)
                setPadding(dp(12), 0, dp(12), 0)
                background = rounded(
                    if (primary) CYAN else PANEL_DARK,
                    if (primary) CYAN else BORDER,
                    12,
                    1
                )
                stateListAnimator = null
            }

        private fun rounded(fill: Int, stroke: Int, radius: Int, strokeWidth: Int) =
            GradientDrawable().apply {
                setColor(fill)
                cornerRadius = dp(radius).toFloat()
                setStroke(dp(strokeWidth), stroke)
            }

        private fun dp(value: Int): Int =
            Math.round(value * resources.displayMetrics.density)

        companion object {
            private const val REQUEST_QR_SCANNER = 8240
        }
    }

    /**
     * Local QR scanner. HTML stays inside the APK and network loads are blocked.
     */
    class QrScannerActivity : android.app.Activity() {
        private var scanner: WebView? = null
        private var pendingFiles: ValueCallback<Array<Uri>>? = null
        private lateinit var nativeStatus: TextView
        private var cameraAllowed = false

        override fun onCreate(state: Bundle?) {
            super.onCreate(state)
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            window.statusBarColor = BG
            window.navigationBarColor = BG
            buildUi()

            cameraAllowed =
                checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
            if (cameraAllowed) {
                loadScanner(true)
            } else {
                nativeStatus.text =
                    "Camera permission is needed for live scanning. You can still import a QR image."
                requestPermissions(arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA)
            }
        }

        override fun onRequestPermissionsResult(
            requestCode: Int,
            permissions: Array<out String>,
            grantResults: IntArray
        ) {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults)
            if (requestCode != REQUEST_CAMERA) return
            cameraAllowed =
                grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
            loadScanner(cameraAllowed)
        }

        @Deprecated("Deprecated in Android")
        override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
            super.onActivityResult(requestCode, resultCode, data)
            if (requestCode == REQUEST_IMAGE) {
                val callback = pendingFiles
                pendingFiles = null
                callback?.onReceiveValue(
                    WebChromeClient.FileChooserParams.parseResult(resultCode, data)
                )
            }
        }

        override fun onDestroy() {
            pendingFiles?.onReceiveValue(null)
            pendingFiles = null
            scanner?.let {
                try {
                    it.loadUrl("about:blank")
                    it.removeJavascriptInterface("HcfQrBridge")
                    it.stopLoading()
                    it.destroy()
                } catch (_: Throwable) {}
            }
            scanner = null
            super.onDestroy()
        }

        private fun buildUi() {
            val root = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(BG)
            }
            val header = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(7), dp(10), dp(7))
            }
            header.addView(button("‹", false).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
                setOnClickListener { finish() }
            }, LinearLayout.LayoutParams(dp(48), dp(44)))

            val labels = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            labels.addView(text("Scan authenticator QR", 18f, TEXT).apply {
                setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            })
            labels.addView(text(
                "Live camera or QR screenshot • processed on this device",
                9f,
                MUTED
            ))
            header.addView(labels, LinearLayout.LayoutParams(0, -2, 1f).apply {
                leftMargin = dp(8)
            })
            root.addView(header, LinearLayout.LayoutParams(-1, -2))

            nativeStatus = text("Preparing secure QR scanner…", 10f, MUTED).apply {
                gravity = Gravity.CENTER
                setPadding(dp(12), dp(5), dp(12), dp(7))
            }
            root.addView(nativeStatus, LinearLayout.LayoutParams(-1, -2))

            scanner = WebView(this).apply {
                setBackgroundColor(BG)
            }
            configureScannerWebView()
            root.addView(scanner, LinearLayout.LayoutParams(-1, 0, 1f))
            setContentView(root)
        }

        @Suppress("SetJavaScriptEnabled")
        private fun configureScannerWebView() {
            val web = scanner ?: return
            web.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = false
                databaseEnabled = false
                allowFileAccess = false
                allowContentAccess = true
                blockNetworkLoads = true
                mediaPlaybackRequiresUserGesture = false
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                safeBrowsingEnabled = true
            }
            web.addJavascriptInterface(QrBridge(), "HcfQrBridge")
            web.webViewClient = WebViewClient()
            web.webChromeClient = object : WebChromeClient() {
                override fun onPermissionRequest(request: PermissionRequest?) {
                    runOnUiThread {
                        if (request == null) return@runOnUiThread
                        val origin = request.origin
                        val localOrigin =
                            origin != null &&
                            "https".equals(origin.scheme, true) &&
                            "hcf-auth.local".equals(origin.host, true)
                        val video = request.resources.any {
                            PermissionRequest.RESOURCE_VIDEO_CAPTURE == it
                        }
                        if (
                            localOrigin &&
                            video &&
                            checkSelfPermission(Manifest.permission.CAMERA) ==
                                PackageManager.PERMISSION_GRANTED
                        ) {
                            request.grant(arrayOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE))
                        } else {
                            request.deny()
                        }
                    }
                }

                override fun onShowFileChooser(
                    webView: WebView?,
                    filePathCallback: ValueCallback<Array<Uri>>?,
                    fileChooserParams: FileChooserParams?
                ): Boolean {
                    if (filePathCallback == null) return false
                    pendingFiles?.onReceiveValue(null)
                    pendingFiles = filePathCallback
                    val choose = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "image/*"
                    }
                    try {
                        startActivityForResult(choose, REQUEST_IMAGE)
                    } catch (_: Throwable) {
                        pendingFiles = null
                        filePathCallback.onReceiveValue(null)
                        Toast.makeText(
                            this@QrScannerActivity,
                            "No image picker is available.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    return true
                }
            }
        }

        private fun loadScanner(allowCamera: Boolean) {
            nativeStatus.text =
                if (allowCamera) "Point the rear camera at the forum QR code."
                else "Live camera is off. Use Import QR image, or grant camera access and retry."
            scanner?.loadDataWithBaseURL(
                "https://hcf-auth.local/scanner/",
                scannerHtml(allowCamera),
                "text/html",
                "UTF-8",
                null
            )
        }

        private fun requestCameraFromPage() {
            runOnUiThread {
                if (
                    checkSelfPermission(Manifest.permission.CAMERA) ==
                    PackageManager.PERMISSION_GRANTED
                ) {
                    cameraAllowed = true
                    loadScanner(true)
                } else {
                    requestPermissions(arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA)
                }
            }
        }

        private inner class QrBridge {
            @JavascriptInterface
            fun onResult(value: String?) {
                runOnUiThread {
                    if (
                        value.isNullOrBlank() ||
                        !value.trim().lowercase(Locale.US).startsWith("otpauth://totp/")
                    ) {
                        Toast.makeText(
                            this@QrScannerActivity,
                            "That QR code is not a TOTP authenticator setup.",
                            Toast.LENGTH_LONG
                        ).show()
                        return@runOnUiThread
                    }
                    setResult(RESULT_OK, Intent().apply {
                        putExtra(EXTRA_QR_VALUE, value.trim())
                        data = Uri.parse(value.trim())
                    })
                    finish()
                }
            }

            @JavascriptInterface
            fun requestCamera() = requestCameraFromPage()

            @JavascriptInterface
            fun scannerStatus(message: String?) {
                runOnUiThread {
                    if (!message.isNullOrBlank()) nativeStatus.text = message.trim()
                }
            }
        }

        private fun scannerHtml(allowCamera: Boolean): String {
            val cameraFlag = if (allowCamera) "true" else "false"
            return """<!doctype html><html><head><meta name='viewport' content='width=device-width,initial-scale=1,viewport-fit=cover'>
<style>*{box-sizing:border-box}html,body{margin:0;width:100%;height:100%;background:#0d1014;color:#eff7fa;font-family:system-ui,-apple-system,sans-serif}body{display:flex;flex-direction:column;overflow:hidden}.stage{position:relative;flex:1;min-height:260px;background:#080b0e;overflow:hidden}video{width:100%;height:100%;object-fit:cover;background:#080b0e}.shade{position:absolute;inset:0;background:linear-gradient(180deg,rgba(0,0,0,.22),transparent 28%,transparent 72%,rgba(0,0,0,.30));pointer-events:none}.frame{position:absolute;left:50%;top:50%;width:min(72vw,320px);height:min(72vw,320px);transform:translate(-50%,-50%);border:2px solid #00b8f0;border-radius:22px;box-shadow:0 0 0 9999px rgba(0,0,0,.26),0 0 22px rgba(0,184,240,.35);pointer-events:none}.frame:before,.frame:after{content:'';position:absolute;inset:16px;border-top:2px solid rgba(255,255,255,.22);border-bottom:2px solid rgba(255,255,255,.22)}.panel{padding:14px 14px calc(14px + env(safe-area-inset-bottom));background:#111b22;border-top:1px solid #29404b}#status{font-size:12px;color:#9baeb7;text-align:center;min-height:34px;line-height:1.35}.ok{color:#55e13b!important}.bad{color:#ff4d57!important}.actions{display:grid;grid-template-columns:1fr 1fr;gap:9px;margin-top:8px}button,.pick{height:48px;border-radius:12px;border:1px solid #29404b;background:#0c151b;color:#eff7fa;font-weight:700;font-size:13px;display:flex;align-items:center;justify-content:center;text-align:center;padding:0 10px}.primary{background:#00b8f0;border-color:#00b8f0;color:#061014}.pick input{display:none}.hint{margin-top:10px;font-size:10px;line-height:1.4;color:#78909a;text-align:center}</style></head>
<body><div class='stage'><video id='video' autoplay playsinline muted></video><div class='shade'></div><div class='frame'></div></div><div class='panel'><div id='status'>Preparing QR detector…</div><div class='actions'><button id='camera' class='primary'>Retry camera</button><label class='pick'>Import QR image<input id='file' type='file' accept='image/*'></label></div><div class='hint'>Only TOTP authenticator QR codes are accepted. QR data is processed locally and is not uploaded.</div></div>
<script>const cameraAllowed=$cameraFlag;const statusEl=document.getElementById('status');const video=document.getElementById('video');let detector=null,stream=null,stopped=false,busy=false,lastMessage=0;function status(m,c){statusEl.textContent=m;statusEl.className=c||'';try{HcfQrBridge.scannerStatus(m)}catch(e){}}async function getDetector(){if(!('BarcodeDetector' in window))throw new Error('Platform QR detector unavailable');const formats=BarcodeDetector.getSupportedFormats?await BarcodeDetector.getSupportedFormats():['qr_code'];if(formats.indexOf('qr_code')<0)throw new Error('QR detection is unavailable on this device');return new BarcodeDetector({formats:['qr_code']});}function stopCamera(){if(stream){stream.getTracks().forEach(t=>t.stop());stream=null;}video.srcObject=null;}function consume(v){const raw=(v||'').trim();if(/^otpauth:\/\/totp\//i.test(raw)){stopped=true;stopCamera();status('Authenticator QR detected.','ok');HcfQrBridge.onResult(raw);return true;}const now=Date.now();if(now-lastMessage>1300){lastMessage=now;status('QR detected, but it is not a TOTP authenticator setup.','bad');}return false;}async function detectSource(source){if(!detector)detector=await getDetector();const codes=await detector.detect(source);if(codes&&codes.length){for(const c of codes){if(consume(c.rawValue))return true;}}return false;}async function loop(){if(stopped||!stream)return;if(!busy&&video.readyState>=2){busy=true;try{await detectSource(video);}catch(e){}finally{busy=false;}}setTimeout(loop,180);}async function startCamera(){stopped=false;stopCamera();try{if(!cameraAllowed){status('Camera permission is not enabled. Tap Retry camera to request it, or import a QR image.','bad');return;}if(!detector)detector=await getDetector();stream=await navigator.mediaDevices.getUserMedia({audio:false,video:{facingMode:{ideal:'environment'},width:{ideal:1280},height:{ideal:720}}});video.srcObject=stream;await video.play();status('Point the camera at the forum authenticator QR code.','');loop();}catch(e){status('Live QR scanning is unavailable. You can still import a QR screenshot.','bad');}}document.getElementById('camera').onclick=()=>{if(!cameraAllowed){try{HcfQrBridge.requestCamera()}catch(e){}return;}startCamera();};document.getElementById('file').addEventListener('change',async e=>{const f=e.target.files&&e.target.files[0];if(!f)return;try{status('Reading QR image…','');const bmp=await createImageBitmap(f);const found=await detectSource(bmp);bmp.close&&bmp.close();if(!found)status('No TOTP authenticator QR code was found in that image.','bad');}catch(err){status('This device could not read that QR image. Use the manual Base32 setup key instead.','bad');}e.target.value='';});window.addEventListener('pagehide',stopCamera);window.addEventListener('beforeunload',stopCamera);(async()=>{try{detector=await getDetector();if(cameraAllowed)startCamera();else status('Camera permission is off. Import a QR image or tap Retry camera.','bad');}catch(e){status('Platform QR detection is unavailable. Use the manual Base32 setup key.','bad');}})();</script></body></html>"""
        }

        private fun text(value: String, sp: Float, color: Int): TextView =
            TextView(this).apply {
                text = value
                setTextColor(color)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
            }

        private fun button(value: String, primary: Boolean): Button =
            Button(this).apply {
                isAllCaps = false
                text = value
                setTextColor(if (primary) Color.BLACK else TEXT)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTypeface(Typeface.DEFAULT, Typeface.BOLD)
                setPadding(dp(12), 0, dp(12), 0)
                background = GradientDrawable().apply {
                    setColor(if (primary) CYAN else PANEL_DARK)
                    cornerRadius = dp(12).toFloat()
                    setStroke(dp(1), if (primary) CYAN else BORDER)
                }
                stateListAnimator = null
            }

        private fun dp(value: Int): Int =
            Math.round(value * resources.displayMetrics.density)

        companion object {
            const val EXTRA_QR_VALUE: String = "hcf_auth_qr_value"
            private const val REQUEST_CAMERA = 8241
            private const val REQUEST_IMAGE = 8242
        }
    }

    class Config(
        secret: String?,
        label: String?,
        issuer: String?,
        algorithm: String?,
        digits: Int,
        period: Int
    ) {
        val secret: String = normalizeSecret(secret)
        val label: String = clean(label)
        val issuer: String = clean(issuer)
        val algorithm: String = normalizeAlgorithm(algorithm)
        val digits: Int = if (digits == 8) 8 else 6
        val period: Int = if (period in 15..120) period else 30

        fun displayLabel(): String =
            when {
                label.isNotEmpty() -> label
                issuer.isNotEmpty() -> issuer
                else -> "Harley's Clan Forum"
            }

        @Throws(Exception::class)
        fun toJson(): JSONObject = JSONObject().apply {
            put("secret", secret)
            put("label", label)
            put("issuer", issuer)
            put("algorithm", algorithm)
            put("digits", digits)
            put("period", period)
        }

        companion object {
            @JvmStatic
            fun manual(secret: String?, label: String?): Config =
                Config(secret, label, "Harley's Clan Forum", "SHA1", 6, 30)

            @JvmStatic
            fun fromOtpAuth(uri: Uri?): Config {
                if (
                    uri == null ||
                    !"otpauth".equals(uri.scheme, true) ||
                    !"totp".equals(uri.host, true)
                ) throw IllegalArgumentException("Not TOTP")

                val secret = uri.getQueryParameter("secret")
                if (secret.isNullOrBlank()) throw IllegalArgumentException("Missing secret")
                val path = uri.path
                val label = if (path == null) "" else Uri.decode(
                    if (path.startsWith("/")) path.substring(1) else path
                )
                val config = Config(
                    secret,
                    label,
                    uri.getQueryParameter("issuer"),
                    uri.getQueryParameter("algorithm"),
                    parseInt(uri.getQueryParameter("digits"), 6),
                    parseInt(uri.getQueryParameter("period"), 30)
                )
                Base32.decode(config.secret)
                return config
            }

            @JvmStatic
            @Throws(Exception::class)
            fun fromJson(value: String): Config {
                val json = JSONObject(value)
                return Config(
                    json.getString("secret"),
                    json.optString("label", ""),
                    json.optString("issuer", "Harley's Clan Forum"),
                    json.optString("algorithm", "SHA1"),
                    json.optInt("digits", 6),
                    json.optInt("period", 30)
                )
            }

            private fun normalizeSecret(value: String?): String =
                value?.uppercase(Locale.US)
                    ?.replace(" ", "")
                    ?.replace("-", "")
                    ?.replace("=", "")
                    ?.trim()
                    .orEmpty()

            private fun normalizeAlgorithm(value: String?): String {
                val normalized = value?.uppercase(Locale.US)
                    ?.replace("-", "")
                    ?.trim() ?: "SHA1"
                return when (normalized) {
                    "SHA256" -> "SHA256"
                    "SHA512" -> "SHA512"
                    else -> "SHA1"
                }
            }

            private fun clean(value: String?): String = value?.trim().orEmpty()
            private fun parseInt(value: String?, fallback: Int): Int =
                value?.toIntOrNull() ?: fallback
        }
    }

    object Totp {
        @JvmStatic
        @Throws(Exception::class)
        fun generate(config: Config, unixSeconds: Long): String {
            val key = Base32.decode(config.secret)
            val counter = ByteBuffer.allocate(8).putLong(unixSeconds / config.period).array()
            val macName = when (config.algorithm) {
                "SHA256" -> "HmacSHA256"
                "SHA512" -> "HmacSHA512"
                else -> "HmacSHA1"
            }
            val mac = Mac.getInstance(macName)
            mac.init(SecretKeySpec(key, macName))
            val hash = mac.doFinal(counter)
            val offset = hash.last().toInt() and 0x0f
            val binary =
                ((hash[offset].toInt() and 0x7f) shl 24) or
                ((hash[offset + 1].toInt() and 0xff) shl 16) or
                ((hash[offset + 2].toInt() and 0xff) shl 8) or
                (hash[offset + 3].toInt() and 0xff)
            val divisor = if (config.digits == 8) 100000000 else 1000000
            return String.format(Locale.US, "%0" + config.digits + "d", binary % divisor)
        }
    }

    object Base32 {
        private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

        @JvmStatic
        fun decode(encoded: String?): ByteArray {
            val input = encoded?.uppercase(Locale.US)
                ?.replace(" ", "")
                ?.replace("-", "")
                ?.replace("=", "")
                .orEmpty()
            if (input.isEmpty()) throw IllegalArgumentException("Empty secret")
            val output = ByteArray((input.length * 5) / 8 + 1)
            var buffer = 0
            var bits = 0
            var count = 0
            for (character in input) {
                val value = ALPHABET.indexOf(character)
                if (value < 0) throw IllegalArgumentException("Invalid Base32")
                buffer = (buffer shl 5) or value
                bits += 5
                if (bits >= 8) {
                    bits -= 8
                    output[count++] = ((buffer shr bits) and 0xff).toByte()
                }
            }
            if (count == 0) throw IllegalArgumentException("Secret too short")
            return Arrays.copyOf(output, count)
        }
    }

    object Vault {
        private const val PREFS = "hcf_authenticator_v1"
        private const val VALUE = "encrypted_totp_config"
        private const val ALIAS = "hcf_authenticator_master_key_v1"
        private val AAD = "HCF-TOTP-V1".toByteArray(StandardCharsets.UTF_8)

        @JvmStatic
        @Throws(Exception::class)
        fun save(context: Context, config: Config) {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            cipher.updateAAD(AAD)
            val encrypted = cipher.doFinal(
                config.toJson().toString().toByteArray(StandardCharsets.UTF_8)
            )
            val iv = cipher.iv
            val packed = ByteBuffer.allocate(1 + iv.size + encrypted.size)
            packed.put(iv.size.toByte()).put(iv).put(encrypted)
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(VALUE, Base64.encodeToString(packed.array(), Base64.NO_WRAP))
                .apply()
        }

        @JvmStatic
        @Throws(Exception::class)
        fun load(context: Context): Config? {
            val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(VALUE, "")
            if (saved.isNullOrEmpty()) return null

            val packed = ByteBuffer.wrap(Base64.decode(saved, Base64.NO_WRAP))
            val ivLength = packed.get().toInt() and 0xff
            if (ivLength < 12 || ivLength > 32 || packed.remaining() <= ivLength) {
                throw IllegalStateException("Bad vault record")
            }
            val iv = ByteArray(ivLength)
            packed.get(iv)
            val encrypted = ByteArray(packed.remaining())
            packed.get(encrypted)

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            cipher.updateAAD(AAD)
            return Config.fromJson(
                String(cipher.doFinal(encrypted), StandardCharsets.UTF_8)
            )
        }

        @JvmStatic
        fun clear(context: Context) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
        }

        @Throws(Exception::class)
        private fun key(): SecretKey {
            val store = KeyStore.getInstance("AndroidKeyStore")
            store.load(null)
            val existing = store.getKey(ALIAS, null)
            if (existing is SecretKey) return existing

            val generator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                "AndroidKeyStore"
            )
            generator.init(
                KeyGenParameterSpec.Builder(
                    ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build()
            )
            return generator.generateKey()
        }
    }

    private fun formatCode(raw: String?): String = when (raw?.length) {
        null -> "--- ---"
        6 -> raw.substring(0, 3) + " " + raw.substring(3)
        8 -> raw.substring(0, 4) + " " + raw.substring(4)
        else -> raw
    }
}
