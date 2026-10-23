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

    // سیگنال همگام‌سازی بارگذاری صفحات و شکار کلیک تماس
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

        // جلوگیری از بسته شدن تصادفی برنامه هنگام اجرای ربات
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

                // شکار کلیک روی دکمه تماس (tel:) بدون باز کردن شماره‌گیر گوشی
                if (url.startsWith("tel:", ignoreCase = true) ||
                    url.startsWith("wtai://", ignoreCase = true)
                ) {
                    val phoneNumber = url.removePrefix("tel:")
                    callClickDeferred?.complete(phoneNumber)
                    return true // مسدود کردن باز شدن شماره‌گیر تلفن
                }

                // مسدود کردن لینک‌های اپلیکیشن‌های خارجی
                if (!url.startsWith("http://") && !url.startsWith("https://")) {
                    return true
                }
                return false
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (url == null || url == "about:blank") return
                // فقط زمانی که بارگذاری صفحه به ۱۰۰٪ رسیده باشد سیگنال تکمیل را می‌فرستیم
                if ((view?.progress ?: 0) >= 100) {
                    pageLoadDeferred?.complete(url)
                }
            }
        }
    }

    private fun startAutomation() {
        keywordText = etKeyword.text.toString().trim()
        targetDomainClean = etTargetDomain.text.toString().trim()
            .removePrefix("https://")
            .removePrefix("http://")
            .removePrefix("www.")
            .trimEnd('/')

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
                "ابتدا دکمه نارنجی را بزنید و سرویس دسترسی حالت هواپیما را فعال کنید!",
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

        // اطمینان از اینکه اگر توقف وسط حالت هواپیما زده شد، حالت هواپیما خاموش شود
        scope.launch {
            val service = AirplaneAccessibilityService.instance
            if (service != null && service.isAirplaneModeOn()) {
                service.ensureAirplaneModeOff { msg -> updateStatus(msg) }
                updateStatus("وضعیت: $reason (حالت هواپیما خاموش شد)")
            }
        }
    }

    /**
     * حلقه ترتیبی و بدون تداخل برای اجرای دقیق تمام مراحل در هر چرخه
     */
    private suspend fun runSequentialBotCycles() {
        try {
            for (loop in 1..totalLoops) {
                if (!isBotRunning) break

                // اطمینان از خاموش بودن حالت هواپیما و وصل بودن اینترنت در ابتدای هر دور
                val service = AirplaneAccessibilityService.instance
                if (service != null && service.isAirplaneModeOn()) {
                    service.ensureAirplaneModeOff { msg -> updateStatus(msg) }
                }

                updateStatus("دور $loop از $totalLoops | بررسی اتصال اینترنت...")
                val hasNet = waitForInternetConnection(25)
                if (!hasNet) {
                    updateStatus("دور $loop | اینترنت وصل نیست! تلاش مجدد برای بازیابی شبکه...")
                    service?.ensureAirplaneModeOff { msg -> updateStatus(msg) }
                    waitForInternetConnection(20)
                }

                // ۱. باز کردن تب جدید (پاکسازی کامل کوکی، کش و تغییر User-Agent)
                resetBrowserForNewTab()
                delay(600)

                val currentIp = fetchPublicIp()
                updateStatus("دور $loop از $totalLoops | IP: $currentIp | جستجوی: $keywordText")

                // ۲. جستجوی کلمه کلیدی در گوگل
                val encodedQuery = withContext(Dispatchers.IO) {
                    URLEncoder.encode(keywordText, "UTF-8")
                }
                loadUrlAndWait("https://www.google.com/search?q=$encodedQuery&hl=fa", 25000L)
                delay(2000)

                // ۳. گشتن به دنبال دامنه هدف در نتایج گوگل (حداکثر تا صفحه ۵)
                var enteredTargetSite = false
                for (serpPage in 1..5) {
                    if (!isBotRunning) break
                    updateStatus("دور $loop | جستجوی $targetDomainClean در صفحه $serpPage گوگل...")

                    val searchRes = evaluateJsSuspend(buildFindTargetInSerpJs())
                    if (searchRes.startsWith("FOUND:")) {
                        val targetUrl = searchRes.removePrefix("FOUND:")
                        updateStatus("دور $loop | سایت پیدا شد! در حال ورود به صفحه اصلی...")
                        val loadedUrl = waitForPageLoadOrFallback(targetUrl, 20000L)
                        enteredTargetSite = loadedUrl.isNotEmpty()
                        break
                    } else if (searchRes == "NEXT_PAGE" && serpPage < 5) {
                        updateStatus("دور $loop | رفتن به صفحه ${serpPage + 1} نتایج گوگل...")
                        waitForNextPageLoad(20000L)
                        delay(2000)
                    } else {
                        break
                    }
                }

                // اگر در ۵ صفحه اول نبود، ورود مستقیم به سایت هدف
                if (!enteredTargetSite && isBotRunning) {
                    updateStatus("دور $loop | ورود مستقیم به $targetDomainClean...")
                    loadUrlAndWait("https://$targetDomainClean", 25000L)
                }

                // ۴. پیمایش زنجیره‌ای رندوم داخل سایت + ماندن در هر صفحه به مدت تعیین‌شده در تنظیمات
                for (step in 0..maxChainSteps) {
                    if (!isBotRunning) break

                    val pageTitle = if (step == 0) "صفحه ورود" else "لینک زنجیره‌ای $step از $maxChainSteps"
                    performDwellAndScroll(loop, pageTitle, dwellTimeSeconds)

                    if (step < maxChainSteps) {
                        updateStatus("دور $loop | انتخاب رندوم لینک داخلی شماره ${step + 1}...")
                        val chosenHref = evaluateJsSuspend(buildRandomInternalLinkJs())

                        if (chosenHref == "NO_LINKS" || chosenHref.isEmpty()) {
                            updateStatus("دور $loop | لینک داخلی دیگری در این صفحه نبود؛ رفتن به مرحله تماس...")
                            break
                        } else {
                            updateStatus("دور $loop | در حال باز کردن لینک ${step + 1}...")
                            waitForPageLoadOrFallback(chosenHref, 22000L)
                            delay(1000)
                        }
                    }
                }

                // ۵. کلیک روی دکمه تماس در پایان چرخه (بدون برقراری تماس تلفنی)
                if (isBotRunning) {
                    updateStatus("دور $loop | پایان زنجیره؛ کلیک روی دکمه تماس...")
                    performSilentCallClick(loop)
                    delay(1200)
                }

                // ۶. روشن و خاموش کردن آنی حالت هواپیما با تایید واقعی سیستم
                if (isBotRunning && loop < totalLoops) {
                    updateStatus("دور $loop تمام شد | اجرای آنی حالت هواپیما برای تغییر IP...")
                    val accService = AirplaneAccessibilityService.instance
                    if (accService != null) {
                        val success = accService.cycleAirplaneModeInstant { msg ->
                            updateStatus("دور $loop | $msg")
                        }
                        if (!success) {
                            updateStatus("دور $loop | هشدار: تغییر حالت هواپیما تایید نشد! کاشی حالت پرواز را در ردیف اول پنل قرار دهید.")
                            delay(2500)
                        } else {
                            updateStatus("دور $loop | حالت هواپیما با موفقیت روشن و بلافاصله خاموش شد.")
                        }
                    }
                }
            }

            if (isBotRunning) {
                stopAutomation("تمام $totalLoops چرخه با موفقیت کامل انجام شد!")
            }

        } catch (e: CancellationException) {
            // لغو طبیعی هنگام زدن دکمه توقف
        } catch (e: Exception) {
            stopAutomation("خطا در چرخه: ${e.localizedMessage ?: "نامشخص"}")
        } finally {
            // تضمین نهایی خاموش بودن حالت هواپیما در هر شرایطی
            withContext(NonCancellable) {
                val service = AirplaneAccessibilityService.instance
                if (service != null && service.isAirplaneModeOn()) {
                    service.ensureAirplaneModeOff { msg -> updateStatus(msg) }
                }
            }
        }
    }

    /**
     * ماندن در صفحه به مدت دقیق تعیین‌شده توسط کاربر در تنظیمات همراه با اسکرول طبیعی و شمارش معکوس
     */
    private suspend fun performDwellAndScroll(loop: Int, stepLabel: String, seconds: Int) {
        for (remaining in seconds downTo 1) {
            updateStatus("دور $loop | $stepLabel | توقف در صفحه: $remaining ثانیه دیگر...")
            if (remaining % 3 == 0) {
                val offset = Random.nextInt(220, 520)
                webView.evaluateJavascript(
                    "window.scrollBy({top: $offset, behavior: 'smooth'});",
                    null
                )
            } else if (remaining == 2 && seconds >= 5) {
                webView.evaluateJavascript(
                    "window.scrollBy({top: -200, behavior: 'smooth'});",
                    null
                )
            }
            delay(1000)
        }
    }

    /**
     * پیدا کردن دکمه تماس و کلیک روی آن بدون باز شدن شماره‌گیر گوشی
     */
    private suspend fun performSilentCallClick(loop: Int) {
        callClickDeferred = CompletableDeferred()
        val res = evaluateJsSuspend(buildClickCallButtonJs())

        if (res == "CALL_FOUND") {
            val clickedNumber = withTimeoutOrNull(4000L) {
                callClickDeferred?.await()
            }
            if (clickedNumber != null) {
                updateStatus("دور $loop | کلیک روی شماره ($clickedNumber) ثبت شد (بدون برقراری تماس).")
            } else {
                updateStatus("دور $loop | رویداد کلیک روی دکمه تماس اجرا شد.")
            }
        } else {
            updateStatus("دور $loop | دکمه تماس در صفحه آخر یافت نشد؛ ادامه به دور بعد...")
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

    /**
     * منتظر ماندن برای باز شدن لینک کلیک‌شده توسط جاوااسکریپت و فالبک به loadUrl در صورت مسدود شدن کلیک
     */
    private suspend fun waitForPageLoadOrFallback(targetUrl: String, timeoutMs: Long): String {
        val deferred = CompletableDeferred<String>()
        pageLoadDeferred = deferred
        val loadedByClick = withTimeoutOrNull(4500L) {
            deferred.await()
        }
        if (!loadedByClick.isNullOrEmpty()) return loadedByClick

        // اگر کلیک جاوااسکریپت باعث تغییر صفحه نشد، مستقیماً آدرس لینک را باز می‌کنیم
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

    private fun buildFindTargetInSerpJs(): String {
        return """
            (function() {
                var domain = "${targetDomainClean.lowercase()}";
                var links = Array.from(document.querySelectorAll('a[href]'));
                for (var i = 0; i < links.length; i++) {
                    var href = links[i].href;
                    var lower = href.toLowerCase();
                    if (lower.indexOf(domain) !== -1 && lower.indexOf('google.') === -1) {
                        links[i].scrollIntoView({behavior: 'smooth', block: 'center'});
                        setTimeout(function() {
                            links[i].click();
                        }, 900);
                        return "FOUND:" + href;
                    }
                }
                window.scrollTo({top: document.body.scrollHeight, behavior: 'smooth'});
                var nextBtn = document.querySelector('#pnnext') || 
                              Array.from(document.querySelectorAll('a, span')).find(function(el) {
                                  var t = el.innerText || '';
                                  return t.includes('بعدی') || t.includes('Next') || t.includes('بیشتر');
                              });
                if (nextBtn) {
                    setTimeout(function() { nextBtn.click(); }, 1000);
                    return "NEXT_PAGE";
                }
                return "NOT_FOUND";
            })();
        """.trimIndent()
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
                }, 900);
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
                    }, 900);
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

    /**
     * بررسی واقعی هم خاموش بودن حالت هواپیما و هم وصل بودن اینترنت دیتای سیم‌کارت
     */
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
