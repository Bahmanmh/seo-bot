package com.seo.autobot

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.delay

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
     * چرخه کامل روشن و خاموش کردن حالت هواپیما بدون نیاز به روت
     */
    suspend fun cycleAirplaneMode(onStatusUpdate: (String) -> Unit): Boolean {
        onStatusUpdate("در حال باز کردن پنل تنظیمات سریع...")
        performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
        delay(1800)

        // تلاش برای پیدا کردن دکمه حالت هواپیما در صفحه اول پنل
        var clickedOn = findAndTapAirplaneTile()
        if (!clickedOn) {
            // اگر پنل نیمه‌باز بود، یک بار دیگر به پایین سوایپ می‌کنیم تا کامل باز شود
            swipeDownQuickSettings()
            delay(1200)
            clickedOn = findAndTapAirplaneTile()
        }

        if (!clickedOn) {
            performGlobalAction(GLOBAL_ACTION_BACK)
            return false
        }

        onStatusUpdate("حالت هواپیما روشن شد. انتظار ۵ ثانیه برای قطع دکل و آزادسازی IP...")
        delay(5500)

        onStatusUpdate("در حال خاموش کردن حالت هواپیما و دریافت IP جدید...")
        findAndTapAirplaneTile()
        delay(1500)

        // بستن پنل تنظیمات سریع و بازگشت به مرورگر
        performGlobalAction(GLOBAL_ACTION_BACK)
        delay(600)
        performGlobalAction(GLOBAL_ACTION_BACK)
        return true
    }

    private fun findAndTapAirplaneTile(): Boolean {
        val rootNode = rootInActiveWindow ?: return false
        val keywords = listOf(
            "حالت پرواز",
            "حالت هواپیما",
            "پرواز",
            "Airplane mode",
            "Airplane",
            "Flight mode",
            "Aeroplane"
        )

        for (keyword in keywords) {
            val nodes = rootNode.findAccessibilityNodeInfosByText(keyword)
            if (!nodes.isNullOrEmpty()) {
                for (node in nodes) {
                    if (clickOrTapNode(node)) return true
                }
            }
        }

        // جستجوی بازگشتی در ContentDescription برای رام‌های سفارشی (Samsung/Xiaomi)
        val descNode = findNodeByDescription(rootNode, keywords)
        if (descNode != null) {
            return clickOrTapNode(descNode)
        }
        return false
    }

    private fun findNodeByDescription(
        node: AccessibilityNodeInfo?,
        keywords: List<String>
    ): AccessibilityNodeInfo? {
        if (node == null) return null
        val desc = node.contentDescription?.toString() ?: ""
        val text = node.text?.toString() ?: ""
        if (keywords.any { desc.contains(it, ignoreCase = true) || text.contains(it, ignoreCase = true) }) {
            return node
        }
        for (i in 0 until node.childCount) {
            val found = findNodeByDescription(node.getChild(i), keywords)
            if (found != null) return found
        }
        return null
    }

    private fun clickOrTapNode(node: AccessibilityNodeInfo): Boolean {
        // ۱. تلاش برای کلیک مستقیم روی خود نود یا والد قابل کلیک آن
        var current: AccessibilityNodeInfo? = node
        while (current != null) {
            if (current.isClickable) {
                val success = current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                if (success) return true
            }
            current = current.parent
        }

        // ۲. فالبک قطعی: استخراج مختصات روی صفحه و ضربه فیزیکی (Gesture Tap)
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
            .addStroke(GestureDescription.StrokeDescription(path, 0, 120))
            .build()
        dispatchGesture(gesture, null, null)
    }

    private fun swipeDownQuickSettings() {
        val displayMetrics = resources.displayMetrics
        val centerX = displayMetrics.widthPixels / 2f
        val startY = displayMetrics.heightPixels * 0.15f
        val endY = displayMetrics.heightPixels * 0.75f

        val path = Path().apply {
            moveTo(centerX, startY)
            lineTo(centerX, endY)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 350))
            .build()
        dispatchGesture(gesture, null, null)
    }
}
