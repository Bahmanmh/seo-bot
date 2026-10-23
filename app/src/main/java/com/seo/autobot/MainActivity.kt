package com.seo.autobot

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.webkit.*
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.*
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.coroutines.resume
import kotlin.random.Random

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var tvStatus: TextView
    private lateinit var settingsPanel: LinearLayout
    private lateinit var etKeyword: EditText
    private lateinit var etTargetDomain: EditText
    private lateinit var etChainCount: EditText
    private lateinit var etDwellTime: EditText
    private lateinit var etLoopCount: EditText
    private lateinit var btnStartStop: Button

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var automationJob: Job? = null
    private var isBotRunning = false

    private var pageLoadDeferred: CompletableDeferred<String>? = null
    private var callClickDeferred: CompletableDeferred<String>? = null

    private var totalLoops = 10
    private var maxChainSteps = 3
    private var dwellTimeSeconds = 10
    private var targetDomainClean = ""
    private var keywordText = ""

    private val mobileUserAgents = listOf(
        "Mozilla/5.0 (Linux; Android 14; SM-S928B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0.6668.70 Mobile Safari/537.36",
        "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.6613.146 Mobile Safari/537.36",
        "Mozilla/5.0 (Linux; Android 13; 23078PND5G) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0.6668.54 Mobile Safari/537.36",
        "Mozilla/5.0 (Linux; Android 14; SM-A556E) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.6533.103 Mobile Safari/537.36"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        settingsPanel = findViewById(R.id.settingsPanel)
        etKeyword = findViewById(R.id.etKeyword)
        etTargetDomain = findViewById(R.id.etTargetDomain)
        etChainCount = findViewById(R.id.etChainCount)
        etDwellTime = findViewById(R.id.etDwellTime)
        etLoopCount = findViewById(R.id.etLoopCount)
        btnStartStop = findViewById(R.id.btnStartStop)
        webView = findViewById(R.id.webView)

        setupWebView()
        loadSavedSettings()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isBotRunning) {
                    Toast.makeText(
                        this@MainActivity,
                        "ربات در حال اجراست. برای خروج ابتدا دکمه توقف را بزنید.",
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        findViewById<Button>(R.id.btnToggleSettings).setOnClickListener {
            settingsPanel.visibility =
                if (settingsPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }

        findViewById<Button>(R.id.btnAccessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            Toast.makeText(
                this,
                "سرویس «اتوماسیون حالت هواپیما (SEO Bot)» را روشن کنید",
                Toast.LENGTH_LONG
            ).show()
        }

        btnStartStop.setOnClickListener {
            if (!isBotRunning) {
                startAutomation()
            } else {
                stopAutomation("متوقف شد توسط کاربر")
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            mediaPlaybackRequiresUserGesture = false
            cacheMode = WebSettings.LOAD_NO_CACHE
        }

        webView.webViewClient = object : WebViewClient() {

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val url = request?.url?.toString() ?: return false

                // ۱. شکار کلیک روی دکمه تماس (tel:) بدون باز شدن شماره‌گیر گوشی
                if (url.startsWith("tel:", ignoreCase = true) ||
                    url.startsWith("wtai://", ignoreCase = true)
                ) {
                    val phoneNumber = url.removePrefix("tel:")
                    callClickDeferred?.complete(phoneNumber)
                    return true
                }

                // ۲. مسدود کردن ورود ناخواسته به اپلیکیشن گوگل مپ در نتایج جستجو
                val lowerUrl = url.lowercase()
                if (lowerUrl.contains("maps.google.") ||
                    lowerUrl.contains("google.com/maps") ||
                    lowerUrl.startsWith("intent:") ||
                    lowerUrl.startsWith("geo:")
                ) {
                    return true
                }

                if (!url.startsWith("http://") && !url.startsWith("https://")) {
                    return true
                }
                return false
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (url == null || url == "about:blank") return
                if ((view?.progress ?: 0) >= 100) {
                    pageLoadDeferred?.complete(url)
                }
            }
        }
    }

    private fun startAutomation() {
        keywordText = etKeyword.text.toString().trim()
        targetDomainClean = etTargetDomain.text.toString().trim()
            .lowercase()
            .removePrefix("https://")
            .removePrefix("http://")
            .removePrefix("www.")
            .substringBefore("/")
            .trim()

        maxChainSteps = (etChainCount.text.toString().toIntOrNull() ?: 3).coerceAtLeast(1)
        dwellTimeSeconds = (etDwellTime.text.toString().toIntOrNull() ?: 10).coerceAtLeast(2)
        totalLoops = (etLoopCount.text.toString().toIntOrNull() ?: 10).coerceAtLeast(1)

        if (keywordText.isEmpty() || targetDomainClean.isEmpty()) {
            Toast.makeText(this, "لطفاً کلمه کلیدی و دامنه سایت را وارد کنید", Toast.LENGTH_SHORT).show()
            return
        }

        if (AirplaneAccessibilityService.instance == null) {
            Toast.makeText(
                this,
                "ابتدا دکمه نارنجی را بزنید و سرویس دسترسی را فعال کنید!",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        saveSettings()
        settingsPanel.visibility = View.GONE
        isBotRunning = true
        btnStartStop.text = "توقف ربات"

        automationJob?.cancel()
        automationJob = scope.launch {
            runSequentialBotCycles()
        }
    }

    private fun stopAutomation(reason: String) {
        isBotRunning = false
        automationJob?.cancel()
        pageLoadDeferred?.cancel()
        callClickDeferred?.cancel()
        webView.stopLoading()
        btnStartStop.text = "۲. شروع ربات"
        updateStatus("وضعیت: $reason")

        scope.launch {
            val service = AirplaneAccessibilityService.instance
            if (service != null && service.isAirplaneModeOn()) {
                service.ensureAirplaneModeOff { msg -> updateStatus(msg) }
                updateStatus("وضعیت: $reason (حالت هواپیما خاموش شد)")
            }
        }
    }

    private suspend fun runSequentialBotCycles() {
        try {
            for (loop in 1..totalLoops) {
                if (!isBotRunning) break

                val service = AirplaneAccessibilityService.instance
                if (service != null && service.isAirplaneModeOn()) {
                    service.ensureAirplaneModeOff { msg -> updateStatus(msg) }
                }

                updateStatus("دور $loop از $totalLoops | بررسی اتصال اینترنت...")
                val hasNet = waitForInternetConnection(25)
                if (!hasNet) {
                    service?.ensureAirplaneModeOff { msg -> updateStatus(msg) }
                    waitForInternetConnection(20)
                }

                // ۱. پاکسازی کامل مرورگر (تب جدید)
                resetBrowserForNewTab()
                delay(600)

                val currentIp = fetchPublicIp()
                updateStatus("دور $loop از $totalLoops | IP: $currentIp | جستجو: $keywordText")

                // ۲. جستجو در گوگل
                val encodedQuery = withContext(Dispatchers.IO) {
                    URLEncoder.encode(keywordText, "UTF-8")
                }
                loadUrlAndWait("https://www.google.com/search?q=$encodedQuery&hl=fa", 25000L)
                delay(1800)

                // ۳. اسکن پله‌به‌پله صفحه نتایج گوگل برای پیدا کردن سایت در همان صفحه اول
                val enteredTargetSite = scanGoogleSerpAndClickTarget(loop)

                if (!enteredTargetSite && isBotRunning) {
                    updateStatus("دور $loop | سایت در نتایج دیده نشد؛ ورود مستقیم به $targetDomainClean...")
                    loadUrlAndWait("https://$targetDomainClean", 25000L)
                }

                // ۴. پیمایش زنجیره‌ای رندوم داخل سایت هدف + توقف به مدت تعیین‌شده در تنظیمات
                for (step in 0..maxChainSteps) {
                    if (!isBotRunning) break

                    val pageTitle = if (step == 0) "صفحه اصلی" else "لینک زنجیره $step از $maxChainSteps"
                    performDwellAndScroll(loop, pageTitle, dwellTimeSeconds)

                    if (step < maxChainSteps) {
                        updateStatus("دور $loop | انتخاب تصادفی لینک داخلی شماره ${step + 1}...")
                        val chosenHref = evaluateJsSuspend(buildRandomInternalLinkJs())

                        if (chosenHref == "NO_LINKS" || chosenHref.isEmpty()) {
                            updateStatus("دور $loop | لینک داخلی دیگری یافت نشد؛ رفتن به مرحله تماس...")
                            break
                        } else {
                            updateStatus("دور $loop | ورود به لینک زنجیره‌ای ${step + 1}...")
                            waitForPageLoadOrFallback(chosenHref, 22000L)
                            delay(1000)
                        }
                    }
                }

                // ۵. کلیک روی دکمه تماس در پایان چرخه (بدون برقراری تماس تلفنی)
                if (isBotRunning) {
                    updateStatus("دور $loop | پایان زنجیره؛ کلیک روی دکمه تماس...")
                    performSilentCallClick(loop)
                    delay(1000)
                }

                // ۶. روشن و خاموش کردن آنی حالت هواپیما در پایان هر دور
                if (isBotRunning) {
                    updateStatus("دور $loop تمام شد | اجرای آنی حالت هواپیما...")
                    val accService = AirplaneAccessibilityService.instance
                    if (accService != null) {
                        val toggled = accService.cycleAirplaneModeInstant { msg ->
                            updateStatus("دور $loop | $msg")
                        }
                        if (toggled) {
                            updateStatus("دور $loop | حالت هواپیما روشن و بلافاصله خاموش شد.")
                        } else {
                            updateStatus("دور $loop | هشدار: کلید حالت هواپیما عمل نکرد! آن را در ردیف اول پنل بالا بگذارید.")
                            delay(2500)
                        }
                    }
                    delay(1500)
                }
            }

            if (isBotRunning) {
                stopAutomation("تمام $totalLoops چرخه با موفقیت انجام شد!")
            }

        } catch (e: CancellationException) {
            // توقف عادی
        } catch (e: Exception) {
            stopAutomation("خطا: ${e.localizedMessage ?: "نامشخص"}")
        } finally {
            withContext(NonCancellable) {
                val service = AirplaneAccessibilityService.instance
                if (service != null && service.isAirplaneModeOn()) {
                    service.ensureAirplaneModeOff { msg -> updateStatus(msg) }
                }
            }
        }
    }

    /**
     * اسکن پله‌به‌پله صفحه نتایج گوگل (از بالا به پایین) بدون کلیک روی گوگل مپ یا بروزرسانی مکان
     */
    private suspend fun scanGoogleSerpAndClickTarget(loop: Int): Boolean {
        for (serpPage in 1..4) {
            if (!isBotRunning) return false
            updateStatus("دور $loop | اسکن صفحه $serpPage گوگل برای $targetDomainClean...")

            // در هر صفحه گوگل، ۷ پله به پایین اسکرول می‌کنیم و در هر پله تمام لینک‌ها را چک می‌کنیم
            for (scrollStep in 1..7) {
                if (!isBotRunning) return false

                // بررسی اینکه آیا در حین کلیک قبلی وارد سایت هدف شده‌ایم یا خیر
                val currentUrl = webView.url?.lowercase() ?: ""
                if (currentUrl.contains(targetDomainClean) && !currentUrl.contains("google.")) {
                    return true
                }

                val foundResult = evaluateJsSuspend(buildCheckTargetInDomJs())
                if (foundResult.startsWith("FOUND:")) {
                    val targetUrl = foundResult.removePrefix("FOUND:")
                    updateStatus("دور $loop | سایت $targetDomainClean در صفحه $serpPage پیدا شد! در حال کلیک...")
                    val loadedUrl = waitForPageLoadOrFallback(targetUrl, 20000L)
                    return loadedUrl.isNotEmpty()
                }

                // اسکرول ملایم به پایین برای بارگذاری بقیه نتایج صفحه اول
                webView.evaluateJavascript(
                    "window.scrollBy({top: 500, behavior: 'smooth'});",
                    null
                )
                delay(1100)
            }

            // اگر بعد از اسکن کل صفحه اول پیدا نشد، فقط روی دکمه واقعی «صفحه بعد / نتایج بیشتر» کلیک می‌کنیم
            val nextRes = evaluateJsSuspend(buildClickRealNextPageJs())
            if (nextRes == "NEXT_CLICKED") {
                updateStatus("دور $loop | بارگذاری نتایج بیشتر گوگل...")
                waitForNextPageLoad(10000L)
                delay(1800)
            } else {
                break
            }
        }
        return false
    }

    /**
     * اسکریپت دقیق پیدا کردن سایت در نتایج گوگل (پشتیبانی از لینک‌های ریدایرکت /url?q= و نادیده گرفتن گوگل مپ)
     */
    private fun buildCheckTargetInDomJs(): String {
        return """
            (function() {
                var domain = "${targetDomainClean.lowercase()}";
                var links = Array.from(document.querySelectorAll('a[href]'));

                for (var i = 0; i < links.length; i++) {
                    var a = links[i];
                    var rawHref = a.href || '';
                    var decoded = '';
                    try { decoded = decodeURIComponent(rawHref).toLowerCase(); } catch(e) { decoded = rawHref.toLowerCase(); }

                    // نادیده گرفتن لینک‌های نقشه گوگل، ورود به حساب و تنظیمات مکان
                    if (decoded.indexOf('maps.google.') !== -1 ||
                        decoded.indexOf('google.com/maps') !== -1 ||
                        decoded.indexOf('accounts.google.') !== -1 ||
                        decoded.indexOf('support.google.') !== -1) {
                        continue;
                    }

                    var realTargetUrl = '';

                    // حالت ۱: لینک مستقیم به سایت هدف
                    if (decoded.indexOf(domain) !== -1 && decoded.indexOf('google.') === -1) {
                        realTargetUrl = rawHref;
                    }
                    // حالت ۲: لینک ریدایرکت گوگل (google.com/url?q=https://target.com...)
                    else if (decoded.indexOf('google.') !== -1 && (decoded.indexOf('/url?') !== -1 || decoded.indexOf('?q=') !== -1)) {
                        try {
                            var urlObj = new URL(rawHref);
                            var qParam = urlObj.searchParams.get('q') || urlObj.searchParams.get('url') || '';
                            if (qParam.toLowerCase().indexOf(domain) !== -1) {
                                realTargetUrl = qParam;
                            }
                        } catch(e) {}
                    }

                    if (realTargetUrl !== '' && a.offsetParent !== null) {
                        a.scrollIntoView({behavior: 'smooth', block: 'center'});
                        setTimeout(function() {
                            a.click();
                        }, 800);
                        return "FOUND:" + realTargetUrl;
                    }
                }
                return "NOT_YET";
            })();
        """.trimIndent()
    }

    /**
     * کلیک فقط روی دکمه واقعی «نتایج بیشتر / صفحه بعد» و فیلتر کردن کامل «جاهای بیشتر» و «بروزرسانی مکان»
     */
    private fun buildClickRealNextPageJs(): String {
        return """
            (function() {
                var nextBtn = document.querySelector('#pnnext') ||
                              document.querySelector('a[aria-label*="Next"]') ||
                              document.querySelector('a[aria-label*="بعدی"]');

                if (!nextBtn) {
                    var candidates = Array.from(document.querySelectorAll('a, button, span'));
                    nextBtn = candidates.find(function(el) {
                        var t = (el.innerText || '').trim();
                        // فیلتر کردن اکید جاهای بیشتر (گوگل مپ) و بروزرسانی مکان
                        if (t.includes('جاهای') || t.includes('مکان') || t.includes('نقشه') || t.includes('بروزرسانی') || t.includes('به روزرسانی')) {
                            return false;
                        }
                        return t === 'نتایج بیشتر' || t === 'بیشتر بدانید' === false && (t === 'بعدی' || t === 'Next' || t === 'More results');
                    });
                }

                if (nextBtn && nextBtn.offsetParent !== null) {
                    nextBtn.scrollIntoView({behavior: 'smooth', block: 'center'});
                    setTimeout(function() { nextBtn.click(); }, 700);
                    return "NEXT_CLICKED";
                }
                return "NO_NEXT";
            })();
        """.trimIndent()
    }

    private suspend fun performDwellAndScroll(loop: Int, stepLabel: String, seconds: Int) {
        for (remaining in seconds downTo 1) {
            if (!isBotRunning) return
            updateStatus("دور $loop | $stepLabel | توقف: $remaining ثانیه...")
            if (remaining % 3 == 0) {
                val offset = Random.nextInt(220, 480)
                webView.evaluateJavascript(
                    "window.scrollBy({top: $offset, behavior: 'smooth'});",
                    null
                )
            } else if (remaining == 2 && seconds >= 5) {
                webView.evaluateJavascript(
                    "window.scrollBy({top: -180, behavior: 'smooth'});",
                    null
                )
            }
            delay(1000)
        }
    }

    private suspend fun performSilentCallClick(loop: Int) {
        callClickDeferred = CompletableDeferred()
        val res = evaluateJsSuspend(buildClickCallButtonJs())

        if (res == "CALL_FOUND") {
            val clickedNumber = withTimeoutOrNull(3500L) {
                callClickDeferred?.await()
            }
            if (clickedNumber != null) {
                updateStatus("دور $loop | کلیک روی تماس ($clickedNumber) ثبت شد (بدون شماره‌گیری).")
            } else {
                updateStatus("دور $loop | کلیک روی دکمه تماس انجام شد.")
            }
        } else {
            updateStatus("دور $loop | دکمه تماس در این صفحه نبود؛ پایان دور...")
        }
    }

    private suspend fun loadUrlAndWait(url: String, timeoutMs: Long): String {
        val deferred = CompletableDeferred<String>()
        pageLoadDeferred = deferred
        webView.loadUrl(url)
        return withTimeoutOrNull(timeoutMs) {
            deferred.await()
        } ?: ""
    }

    private suspend fun waitForNextPageLoad(timeoutMs: Long): String {
        val deferred = CompletableDeferred<String>()
        pageLoadDeferred = deferred
        return withTimeoutOrNull(timeoutMs) {
            deferred.await()
        } ?: ""
    }

    private suspend fun waitForPageLoadOrFallback(targetUrl: String, timeoutMs: Long): String {
        val deferred = CompletableDeferred<String>()
        pageLoadDeferred = deferred
        val loadedByClick = withTimeoutOrNull(4500L) {
            deferred.await()
        }
        if (!loadedByClick.isNullOrEmpty() && !loadedByClick.contains("google.com/search")) {
            return loadedByClick
        }
        return loadUrlAndWait(targetUrl, timeoutMs)
    }

    private suspend fun evaluateJsSuspend(script: String): String =
        suspendCancellableCoroutine { cont ->
            webView.evaluateJavascript(script) { raw ->
                val cleaned = raw?.trim()?.removeSurrounding("\"")
                    ?.replace("\\\"", "\"")
                    ?.replace("\\\\", "\\") ?: ""
                if (cont.isActive) cont.resume(cleaned)
            }
        }

    private fun buildRandomInternalLinkJs(): String {
        return """
            (function() {
                var domain = "${targetDomainClean.lowercase()}";
                var currentUrl = window.location.href.split('#')[0];
                var allLinks = Array.from(document.querySelectorAll('a[href]'));
                var validLinks = allLinks.filter(function(a) {
                    var href = a.href.trim();
                    var lower = href.toLowerCase();
                    return lower.indexOf(domain) !== -1 &&
                           href.split('#')[0] !== currentUrl &&
                           !lower.startsWith('tel:') &&
                           !lower.startsWith('mailto:') &&
                           !lower.startsWith('javascript:') &&
                           !lower.match(/\.(jpg|jpeg|png|gif|pdf|zip|mp4|webp)$/i) &&
                           a.offsetParent !== null;
                });
                if (validLinks.length === 0) return "NO_LINKS";
                var chosen = validLinks[Math.floor(Math.random() * validLinks.length)];
                chosen.scrollIntoView({behavior: 'smooth', block: 'center'});
                setTimeout(function() {
                    chosen.click();
                }, 800);
                return chosen.href;
            })();
        """.trimIndent()
    }

    private fun buildClickCallButtonJs(): String {
        return """
            (function() {
                var callLink = document.querySelector('a[href^="tel:"]');
                if (!callLink) {
                    var candidates = Array.from(document.querySelectorAll('a, button'));
                    callLink = candidates.find(function(el) {
                        var t = (el.innerText || el.getAttribute('aria-label') || '').toLowerCase();
                        return t.includes('تماس') || t.includes('call') || t.includes('0912') || t.includes('۰۹۱۲') || t.includes('0919') || t.includes('۰۹۱۹');
                    });
                }
                if (callLink) {
                    callLink.scrollIntoView({behavior: 'smooth', block: 'center'});
                    setTimeout(function() {
                        ['touchstart', 'touchend', 'mousedown', 'mouseup', 'click'].forEach(function(evtType) {
                            var evt = new Event(evtType, {bubbles: true, cancelable: true});
                            callLink.dispatchEvent(evt);
                        });
                        if (callLink.href && callLink.href.toLowerCase().startsWith('tel:')) {
                            window.location.href = callLink.href;
                        } else {
                            window.location.href = 'tel:0000000000';
                        }
                    }, 800);
                    return "CALL_FOUND";
                }
                return "NO_CALL_BTN";
            })();
        """.trimIndent()
    }

    private fun resetBrowserForNewTab() {
        val cookieManager = CookieManager.getInstance()
        cookieManager.removeAllCookies(null)
        cookieManager.flush()

        WebStorage.getInstance().deleteAllData()
        webView.clearCache(true)
        webView.clearHistory()
        webView.clearFormData()

        webView.settings.userAgentString = mobileUserAgents.random()
        webView.loadUrl("about:blank")
    }

    private suspend fun waitForInternetConnection(maxAttempts: Int): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val service = AirplaneAccessibilityService.instance
        for (attempt in 1..maxAttempts) {
            val airplaneOff = service?.isAirplaneModeOn() != true
            val network = cm.activeNetwork
            val caps = cm.getNetworkCapabilities(network)
            val isConnected = airplaneOff && caps != null &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            if (isConnected) return true
            delay(1000)
        }
        return false
    }

    private suspend fun fetchPublicIp(): String = withContext(Dispatchers.IO) {
        try {
            val conn = URL("https://api.ipify.org").openConnection() as HttpURLConnection
            conn.connectTimeout = 4000
            conn.readTimeout = 4000
            conn.inputStream.bufferedReader().use { it.readText().trim() }
        } catch (e: Exception) {
            "در حال دریافت..."
        }
    }

    private fun updateStatus(message: String) {
        runOnUiThread {
            tvStatus.text = message
        }
    }

    private fun saveSettings() {
        getSharedPreferences("bot_prefs", Context.MODE_PRIVATE).edit().apply {
            putString("keyword", keywordText)
            putString("domain", targetDomainClean)
            putInt("chain", maxChainSteps)
            putInt("dwell", dwellTimeSeconds)
            putInt("loops", totalLoops)
            apply()
        }
    }

    private fun loadSavedSettings() {
        val prefs = getSharedPreferences("bot_prefs", Context.MODE_PRIVATE)
        etKeyword.setText(prefs.getString("keyword", ""))
        etTargetDomain.setText(prefs.getString("domain", ""))
        etChainCount.setText(prefs.getInt("chain", 3).toString())
        etDwellTime.setText(prefs.getInt("dwell", 10).toString())
        etLoopCount.setText(prefs.getInt("loops", 10).toString())
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
