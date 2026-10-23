package com.seo.autobot

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

class AirplaneAccessibilityService : AccessibilityService() {

    companion object {
        var instance: AirplaneAccessibilityService? = null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        if (instance == this) instance = null
    }

    /**
     * خواندن وضعیت واقعی حالت هواپیما از هسته سیستم‌عامل اندروید
     */
    fun isAirplaneModeOn(): Boolean {
        return try {
            Settings.Global.getInt(
                contentResolver,
                Settings.Global.AIRPLANE_MODE_ON,
                0
            ) != 0
        } catch (e: Exception) {
            false
        }
    }

    /**
     * چرخه آنی و تاییدشده حالت هواپیما:
     * ۱. روشن کردن و تایید روشن شدن واقعی
     * ۲. خاموش کردن آنی به محض روشن شدن و تایید خاموش شدن واقعی
     * ۳. تضمین خاموش شدن در صورت توقف یا لغو ناگهانی (بلوک NonCancellable)
     */
    suspend fun cycleAirplaneModeInstant(onStatusUpdate: (String) -> Unit): Boolean {
        var turnedOnVerified = false
        try {
            // اگر از قبل روشن مانده بود، ابتدا وضعیت را ثبت می‌کنیم
            if (isAirplaneModeOn()) {
                turnedOnVerified = true
            } else {
                onStatusUpdate("در حال روشن کردن حالت هواپیما...")
                turnedOnVerified = setAirplaneModeVerified(targetStateOn = true, onStatusUpdate = onStatusUpdate)
            }

            if (!turnedOnVerified) {
                onStatusUpdate("خطا: روشن شدن حالت هواپیما تایید نشد!")
                closeQuickSettingsSafely()
                return false
            }

            // مکث بسیار کوتاه (۲۵۰ میلی‌ثانیه) فقط برای ثبت انیمیشن دکمه در اندروید و سپس خاموش کردن آنی
            delay(250)
            onStatusUpdate("حالت هواپیما روشن شد! در حال خاموش کردن آنی...")

            val turnedOffVerified = setAirplaneModeVerified(targetStateOn = false, onStatusUpdate = onStatusUpdate)
            closeQuickSettingsSafely()

            return turnedOnVerified && turnedOffVerified && !isAirplaneModeOn()

        } finally {
            // مسیر تضمین‌شده: اگر کاربر وسط کار دکمه توقف را زد یا خطایی رخ داد، گوشی در حالت هواپیما نماند
            withContext(NonCancellable) {
                if (isAirplaneModeOn()) {
                    onStatusUpdate("ایمن‌سازی: در حال خاموش کردن اجباری حالت هواپیما...")
                    setAirplaneModeVerified(targetStateOn = false, onStatusUpdate = onStatusUpdate)
                }
                closeQuickSettingsSafely()
            }
        }
    }

    /**
     * متد کمکی برای خاموش کردن تضمینی از بیرون سرویس (هنگام توقف دستی ربات)
     */
    suspend fun ensureAirplaneModeOff(onStatusUpdate: (String) -> Unit): Boolean {
        return withContext(NonCancellable) {
            if (!isAirplaneModeOn()) return@withContext true
            val result = setAirplaneModeVerified(targetStateOn = false, onStatusUpdate = onStatusUpdate)
            closeQuickSettingsSafely()
            result && !isAirplaneModeOn()
        }
    }

    /**
     * تغییر وضعیت حالت هواپیما به مقدار هدف (روشن یا خاموش) به همراه بررسی واقعی و تلاش مجدد
     */
    private suspend fun setAirplaneModeVerified(
        targetStateOn: Boolean,
        onStatusUpdate: (String) -> Unit
    ): Boolean {
        if (isAirplaneModeOn() == targetStateOn) return true

        for (attempt in 1..4) {
            // بررسی اینکه آیا کاشی حالت هواپیما هم‌اکنون در صفحه دیده می‌شود یا پنل بسته شده است
            var tileNode = findAirplaneTileNode()
            if (tileNode == null) {
                performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
                delay(700)
                tileNode = findAirplaneTileNode()
            }

            if (tileNode == null) {
                swipeDownQuickSettings()
                delay(700)
                tileNode = findAirplaneTileNode()
            }

            if (tileNode != null) {
                // در تلاش اول از کلیک استاندارد یا ژست، و در تلاش‌های بعدی مستقیماً از ضربه فیزیکی روی مختصات استفاده می‌کنیم
                val forceGesture = attempt >= 2
                clickOrTapNode(tileNode, forceGesture)

                // پایش آنی (هر ۱۰۰ میلی‌ثانیه) برای تشخیص تغییر واقعی وضعیت سیستم
                for (poll in 1..22) {
                    delay(100)
                    if (isAirplaneModeOn() == targetStateOn) {
                        return true
                    }
                }
            }

            val targetText = if (targetStateOn) "روشن" else "خاموش"
            onStatusUpdate("تلاش مجدد ($attempt از 4) برای $targetText کردن حالت هواپیما...")
            delay(300)
        }

        return isAirplaneModeOn() == targetStateOn
    }

    /**
     * بستن ایمن پنل تنظیمات سریع فقط در صورتی که پنل واقعاً باز باشد (جلوگیری از بسته شدن خود برنامه)
     */
    private suspend fun closeQuickSettingsSafely() {
        for (i in 1..3) {
            val rootPkg = rootInActiveWindow?.packageName?.toString() ?: ""
            // اگر پنجره فعلی مربوط به برنامه خودمان است، یعنی پنل بسته شده و نباید Back بزنیم
            if (rootPkg == packageName || rootPkg.isEmpty()) {
                break
            }
            performGlobalAction(GLOBAL_ACTION_BACK)
            delay(450)
        }
    }

    private fun findAirplaneTileNode(): AccessibilityNodeInfo? {
        val rootNode = rootInActiveWindow ?: return null
        val keywords = listOf(
            "حالت پرواز",
            "حالت هواپیما",
            "Airplane mode",
            "Airplane",
            "Flight mode",
            "Aeroplane",
            "پرواز"
        )

        for (keyword in keywords) {
            val nodes = rootNode.findAccessibilityNodeInfosByText(keyword)
            if (!nodes.isNullOrEmpty()) {
                for (node in nodes) {
                    if (node.isVisibleToUser) return node
                }
            }
        }

        return findNodeByDescription(rootNode, keywords)
    }

    private fun findNodeByDescription(
        node: AccessibilityNodeInfo?,
        keywords: List<String>
    ): AccessibilityNodeInfo? {
        if (node == null) return null
        val desc = node.contentDescription?.toString() ?: ""
        val text = node.text?.toString() ?: ""
        if (node.isVisibleToUser && keywords.any {
                desc.contains(it, ignoreCase = true) || text.contains(it, ignoreCase = true)
            }
        ) {
            return node
        }
        for (i in 0 until node.childCount) {
            val found = findNodeByDescription(node.getChild(i), keywords)
            if (found != null) return found
        }
        return null
    }

    private fun clickOrTapNode(node: AccessibilityNodeInfo, forceGesture: Boolean): Boolean {
        if (!forceGesture) {
            var current: AccessibilityNodeInfo? = node
            while (current != null) {
                if (current.isClickable) {
                    if (current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                        return true
                    }
                }
                current = current.parent
            }
        }

        val rect = Rect()
        node.getBoundsInScreen(rect)
        if (!rect.isEmpty && rect.centerX() > 0 && rect.centerY() > 0) {
            tapAtCoordinates(rect.centerX().toFloat(), rect.centerY().toFloat())
            return true
        }
        return false
    }

    private fun tapAtCoordinates(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
            .build()
        dispatchGesture(gesture, null, null)
    }

    private fun swipeDownQuickSettings() {
        val dm = resources.displayMetrics
        val centerX = dm.widthPixels / 2f
        val startY = dm.heightPixels * 0.12f
        val endY = dm.heightPixels * 0.70f

        val path = Path().apply {
            moveTo(centerX, startY)
            lineTo(centerX, endY)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 300))
            .build()
        dispatchGesture(gesture, null, null)
    }
}
