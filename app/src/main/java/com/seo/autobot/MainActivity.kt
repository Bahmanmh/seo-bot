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
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.*
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.random.Random

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var tvStatus: TextView
    private lateinit var settingsPanel: LinearLayout
    private lateinit var etKeyword: EditText
    private lateinit var etTargetDomain: EditText
    private lateinit var etChainCount: EditText
    private lateinit var etLoopCount: EditText
    private lateinit var btnStartStop: Button

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var automationJob: Job? = null

    private enum class BotState {
        IDLE,
        SEARCHING_SERP,
        INSIDE_TARGET_CHAIN,
        CLICKING_CALL_BUTTON,
        CYCLING_AIRPLANE
    }

    private var currentState = BotState.IDLE
    private var currentLoop = 0
    private var totalLoops = 10
    private var currentChainStep = 0
    private var maxChainSteps = 3
    private var currentSerpPage = 1
    private var targetDomainClean = ""
    private var keywordText = ""
    private var callButtonClickedInCycle = false

    // لیست User-Agentهای واقعی مرورگر کروم اندروید برای چرخش در هر تب جدید
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
        etLoopCount = findViewById(R.id.etLoopCount)
        btnStartStop = findViewById(R.id.btnStartStop)
        webView = findViewById(R.id.webView)

        setupWebView()
        loadSavedSettings()

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
            if (currentState == BotState.IDLE) {
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

            /**
             * شکار لینک تماس (tel:) در لحظه کلیک:
             * رویداد کلیک در جاوااسکریپت و آنالیتیکس سایت ثبت می‌شود،
             * اما با برگرداندن true جلوی باز شدن شماره‌گیر تلفن گرفته می‌شود.
             */
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val url = request?.url?.toString() ?: return false

                if (url.startsWith("tel:", ignoreCase = true) ||
                    url.startsWith("wtai://", ignoreCase = true)
                ) {
                    val phoneNumber = url.removePrefix("tel:")
                    updateStatus("کلیک روی تماس ($phoneNumber) ثبت شد (بدون برقراری تماس).")
                    callButtonClickedInCycle = true

                    // پایان چرخه فعلی و رفتن به مرحله تب جدید + حالت هواپیما
                    proceedToNextCycleAfterCall()
                    return true // مسدود کردن باز شدن Dialer گوشی
                }

                // مسدود کردن اپلیکیشن‌های خارجی مثل واتساپ/تلگرام/اینستاگرام در حین کلیک رندوم
                if (!url.startsWith("http://") && !url.startsWith("https://")) {
                    return true
                }
                return false
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (url == null || url == "about:blank" || currentState == BotState.IDLE) return
                handlePageLoaded(url)
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

        maxChainSteps = etChainCount.text.toString().toIntOrNull() ?: 3
        totalLoops = etLoopCount.text.toString().toIntOrNull() ?: 10

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
        btnStartStop.text = "توقف ربات"
        currentLoop = 0

        startSingleCycle()
    }

    private fun stopAutomation(reason: String) {
        automationJob?.cancel()
        currentState = BotState.IDLE
        webView.stopLoading()
        btnStartStop.text = "۲. شروع ربات"
        updateStatus("وضعیت: $reason")
    }

    private fun startSingleCycle() {
        automationJob?.cancel()
        automationJob = scope.launch {
            currentLoop++
            if (currentLoop > totalLoops) {
                stopAutomation("تمام $totalLoops چرخه با موفقیت به پایان رسید!")
                return@launch
            }

            currentChainStep = 0
            currentSerpPage = 1
            callButtonClickedInCycle = false

            // ۱. شبیه‌سازی تب کاملاً جدید (پاکسازی کوکی، کش و چرخش User-Agent)
            resetBrowserForNewTab()
            delay(800)

            // ۲. بررسی IP فعلی و شروع جستجوی کلمه کلیدی در گوگل
            val currentIp = fetchPublicIp()
            currentState = BotState.SEARCHING_SERP
            updateStatus("دور $currentLoop از $totalLoops | IP: $currentIp | جستجوی: $keywordText")

            val encodedQuery = withContext(Dispatchers.IO) {
                URLEncoder.encode(keywordText, "UTF-8")
            }
            webView.loadUrl("https://www.google.com/search?q=$encodedQuery&hl=fa")
        }
    }

    private fun handlePageLoaded(url: String) {
        automationJob?.cancel()
        automationJob = scope.launch {
            when (currentState) {
                BotState.SEARCHING_SERP -> {
                    if (url.contains("google.com")) {
                        delay(Random.nextLong(2500, 4200))
                        findTargetInGoogleSerp()
                    } else if (url.contains(targetDomainClean, ignoreCase = true)) {
                        // وارد سایت هدف شدیم (صفحه اصلی / لندینگ)
                        currentState = BotState.INSIDE_TARGET_CHAIN
                        currentChainStep = 0
                        handleTargetSiteNavigation()
                    }
                }

                BotState.INSIDE_TARGET_CHAIN -> {
                    handleTargetSiteNavigation()
                }

                else -> {}
            }
        }
    }

    /**
     * جستجوی دامنه هدف در نتایج گوگل با اسکرول طبیعی و رفتن به صفحات بعدی در صورت نیاز
     */
    private suspend fun findTargetInGoogleSerp() {
        updateStatus("دور $currentLoop | اسکن نتایج گوگل (صفحه $currentSerpPage) برای $targetDomainClean...")

        val jsFindTarget = """
            (function() {
                var domain = "${targetDomainClean.lowercase()}";
                var links = Array.from(document.querySelectorAll('a[href]'));
                for (var i = 0; i < links.length; i++) {
                    var href = links[i].href.toLowerCase();
                    if (href.indexOf(domain) !== -1 && href.indexOf('google.') === -1) {
                        links[i].scrollIntoView({behavior: 'smooth', block: 'center'});
                        setTimeout(function() {
                            links[i].click();
                        }, 1400);
                        return "FOUND";
                    }
                }
                window.scrollTo({top: document.body.scrollHeight, behavior: 'smooth'});
                var nextBtn = document.querySelector('#pnnext') || 
                              Array.from(document.querySelectorAll('a, span')).find(el => 
                                  el.innerText.includes('بعدی') || 
                                  el.innerText.includes('Next') || 
                                  el.innerText.includes('بیشتر')
                              );
                if (nextBtn) {
                    setTimeout(function() { nextBtn.click(); }, 1800);
                    return "NEXT_PAGE";
                }
                return "NOT_FOUND";
            })();
        """.trimIndent()

        webView.evaluateJavascript(jsFindTarget) { result ->
            val cleanRes = result?.replace("\"", "") ?: ""
            when (cleanRes) {
                "FOUND" -> {
                    updateStatus("دور $currentLoop | سایت $targetDomainClean پیدا شد! در حال ورود...")
                    currentState = BotState.INSIDE_TARGET_CHAIN
                }
                "NEXT_PAGE" -> {
                    currentSerpPage++
                    if (currentSerpPage > 5) {
                        updateStatus("سایت در ۵ صفحه اول پیدا نشد؛ ورود مستقیم...")
                        currentState = BotState.INSIDE_TARGET_CHAIN
                        webView.loadUrl("https://$targetDomainClean")
                    }
                }
                else -> {
                    webView.loadUrl("https://$targetDomainClean")
                }
            }
        }
    }

    /**
     * مدیریت پیمایش زنجیره‌ای رندوم (لینک ۱ -> لینک X -> لینک Y) و در نهایت کلیک دکمه تماس
     */
    private suspend fun handleTargetSiteNavigation() {
        // اسکرول طبیعی به پایین و بالا برای شبیه‌سازی مطالعه صفحه توسط کاربر واقعی
        val dwellSeconds = Random.nextInt(8, 15)
        updateStatus("دور $currentLoop | گام زنجیره: $currentChainStep از $maxChainSteps | مطالعه صفحه ($dwellSeconds ثانیه)...")

        simulateHumanScroll(dwellSeconds)

        if (currentChainStep < maxChainSteps) {
            currentChainStep++
            updateStatus("دور $currentLoop | انتخاب تصادفی لینک زنجیره‌ای شماره $currentChainStep...")
            clickRandomInternalLink()
        } else {
            // پایان زنجیره صفحات -> مرحله کلیک روی دکمه تماس بدون برقراری تماس
            currentState = BotState.CLICKING_CALL_BUTTON
            updateStatus("دور $currentLoop | پایان زنجیره؛ جستجو و کلیک روی دکمه تماس...")
            clickCallButtonOnPage()
        }
    }

    private suspend fun simulateHumanScroll(seconds: Int) {
        val steps = (seconds / 2).coerceAtLeast(2)
        for (i in 1..steps) {
            val scrollOffset = Random.nextInt(250, 650)
            webView.evaluateJavascript(
                "window.scrollBy({top: $scrollOffset, behavior: 'smooth'});",
                null
            )
            delay(1800)
        }
        webView.evaluateJavascript("window.scrollBy({top: -250, behavior: 'smooth'});", null)
        delay(1000)
    }

    /**
     * پیدا کردن تمام لینک‌های داخلی معتبر در صفحه فعلی و کلیک رندوم روی یکی از آن‌ها
     */
    private fun clickRandomInternalLink() {
        val jsRandomLink = """
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
                    window.location.href = chosen.href;
                }, 1200);
                return chosen.href;
            })();
        """.trimIndent()

        webView.evaluateJavascript(jsRandomLink) { result ->
            val cleanRes = result?.replace("\"", "") ?: "NO_LINKS"
            if (cleanRes == "NO_LINKS") {
                // اگر صفحه فعلی لینک داخلی دیگری نداشت، مستقیماً سراغ کلیک دکمه تماس می‌رویم
                scope.launch { clickCallButtonOnPage() }
            }
        }
    }

    /**
     * پیدا کردن دکمه تماس (tel:) در صفحه آخر، اجرای رویدادهای کلیک DOM و ارسال به WebViewClient
     */
    private suspend fun clickCallButtonOnPage() {
        val jsClickCall = """
            (function() {
                var callLink = document.querySelector('a[href^="tel:"]');
                if (!callLink) {
                    var candidates = Array.from(document.querySelectorAll('a, button'));
                    callLink = candidates.find(function(el) {
                        var t = (el.innerText || el.getAttribute('aria-label') || '').toLowerCase();
                        return t.includes('تماس') || t.includes('call') || t.includes('0912') || t.includes('۰۹۱۲');
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
                    }, 1200);
                    return "CALL_FOUND";
                }
                return "NO_CALL_BTN";
            })();
        """.trimIndent()

        webView.evaluateJavascript(jsClickCall) { result ->
            val cleanRes = result?.replace("\"", "") ?: ""
            if (cleanRes == "NO_CALL_BTN") {
                updateStatus("دور $currentLoop | دکمه تماس در این صفحه نبود؛ شبیه‌سازی رویداد تماس و پایان دور...")
                proceedToNextCycleAfterCall()
            }
        }

        // تایم‌اوت اطمینان در صورتی که اسکریپت سایت جلوی تغییر آدرس را گرفته باشد
        delay(4000)
        if (!callButtonClickedInCycle && currentState == BotState.CLICKING_CALL_BUTTON) {
            proceedToNextCycleAfterCall()
        }
    }

    /**
     * اجرای مرحله پایانی هر دور:
     * ۱. باز کردن تب جدید و پاکسازی کامل نشست
     * ۲. روشن و خاموش کردن حالت هواپیما
     * ۳. انتظار برای اتصال مجدد اینترنت سیم‌کارت و شروع دور بعدی
     */
    private fun proceedToNextCycleAfterCall() {
        if (currentState == BotState.CYCLING_AIRPLANE || currentState == BotState.IDLE) return
        currentState = BotState.CYCLING_AIRPLANE

        automationJob?.cancel()
        automationJob = scope.launch {
            delay(1200)
            updateStatus("دور $currentLoop تمام شد. در حال بستن تب و پاکسازی کوکی‌ها...")
            resetBrowserForNewTab()

            val service = AirplaneAccessibilityService.instance
            if (service != null) {
                val toggled = service.cycleAirplaneMode { msg -> updateStatus(msg) }
                if (!toggled) {
                    updateStatus("هشدار: آیکون حالت هواپیما در پنل بالا پیدا نشد! کاشی حالت پرواز را در ردیف اول پنل قرار دهید.")
                    delay(3000)
                }
            }

            updateStatus("منتظر اتصال مجدد اینترنت سیم‌کارت...")
            waitForInternetConnection()
            delay(2000)

            // شروع دور بعدی
            startSingleCycle()
        }
    }

    private fun resetBrowserForNewTab() {
        val cookieManager = CookieManager.getInstance()
        cookieManager.removeAllCookies(null)
        cookieManager.flush()

        WebStorage.getInstance().deleteAllData()
        webView.clearCache(true)
        webView.clearHistory()
        webView.clearFormData()

        // انتخاب یک User-Agent جدید برای تب جدید
        webView.settings.userAgentString = mobileUserAgents.random()
        webView.loadUrl("about:blank")
    }

    private suspend fun waitForInternetConnection() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        for (attempt in 1..30) {
            val network = cm.activeNetwork
            val caps = cm.getNetworkCapabilities(network)
            val isConnected = caps != null &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            if (isConnected) return
            delay(1000)
        }
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
            putInt("loops", totalLoops)
            apply()
        }
    }

    private fun loadSavedSettings() {
        val prefs = getSharedPreferences("bot_prefs", Context.MODE_PRIVATE)
        etKeyword.setText(prefs.getString("keyword", ""))
        etTargetDomain.setText(prefs.getString("domain", ""))
        etChainCount.setText(prefs.getInt("chain", 3).toString())
        etLoopCount.setText(prefs.getInt("loops", 10).toString())
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
