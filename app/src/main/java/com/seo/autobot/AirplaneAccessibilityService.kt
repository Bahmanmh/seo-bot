package com.seo.autobot

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
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
     * خواندن وضعیت واقعی حالت هواپیما از سیستم‌عامل اندروید
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
     * روشن و خاموش کردن آنی (Instant) حالت هواپیما:
     * به محض اینکه روشن شدن در سیستم ثبت شد، بلافاصله در همان لحظه خاموش می‌شود.
     */
    suspend fun cycleAirplaneModeInstant(onStatusUpdate: (String) -> Unit): Boolean {
        try {
            onStatusUpdate("باز کردن پنل بالای گوشی برای حالت هواپیما...")

            // ۱. باز کردن اجباری پنل تنظیمات سریع (Quick Settings)
            openQuickSettingsPanel()

            // ۲. روشن کردن حالت هواپیما و انتظار آنی (هر ۵۰ میلی‌ثانیه) برای تایید روشن شدن
            val turnedOn = toggleAirplaneToState(
                targetStateOn = true,
                onStatusUpdate = onStatusUpdate
            )

            if (!turnedOn) {
                onStatusUpdate("خطا: کلید حالت هواپیما در پنل بالا پیدا یا روشن نشد!")
                closeQuickSettingsSafely()
                return false
            }

            // ۳. به محض روشن شدن، بدون هیچ مکثی بلافاصله خاموش می‌کنیم
            onStatusUpdate("حالت هواپیما روشن شد! خاموش کردن آنی...")
            val turnedOff = toggleAirplaneToState(
                targetStateOn = false,
                onStatusUpdate = onStatusUpdate
            )

            closeQuickSettingsSafely()
            return turnedOn && turnedOff && !isAirplaneModeOn()

        } finally {
            // تضمین ۱۰۰٪: اگر وسط کار توقف زده شد یا خطایی رخ داد، حالت هواپیما روشن نماند
            withContext(NonCancellable) {
                if (isAirplaneModeOn()) {
                    onStatusUpdate("در حال خاموش کردن تضمینی حالت هواپیما...")
                    openQuickSettingsPanel()
                    toggleAirplaneToState(targetStateOn = false, onStatusUpdate = onStatusUpdate)
                }
                closeQuickSettingsSafely()
            }
        }
    }

    suspend fun ensureAirplaneModeOff(onStatusUpdate: (String) -> Unit): Boolean {
        return withContext(NonCancellable) {
            if (!isAirplaneModeOn()) return@withContext true
            openQuickSettingsPanel()
            val res = toggleAirplaneToState(targetStateOn = false, onStatusUpdate = onStatusUpdate)
            closeQuickSettingsSafely()
            res && !isAirplaneModeOn()
        }
    }

    private suspend fun openQuickSettingsPanel() {
        performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
        delay(650)
        // اگر کاشی حالت هواپیما در حالت خلاصه پیدا نشد، یک بار پنل را به پایین می‌کشیم تا کامل باز شود
        if (findSystemAirplaneTile() == null) {
            swipeDownQuickSettings()
            delay(650)
        }
    }

    /**
     * تغییر وضعیت به مقدار هدف با بررسی ۵۰ میلی‌ثانیه‌ای و ذخیره مختصات کلید برای ضربه آنی
     */
    private suspend fun toggleAirplaneToState(
        targetStateOn: Boolean,
        onStatusUpdate: (String) -> Unit
    ): Boolean {
        if (isAirplaneModeOn() == targetStateOn) return true

        var lastKnownBounds: Rect? = null

        for (attempt in 1..5) {
            val tileNode = findSystemAirplaneTile()
            if (tileNode != null) {
                val rect = Rect()
                tileNode.getBoundsInScreen(rect)
                if (!rect.isEmpty && rect.centerX() > 0 && rect.centerY() > 0) {
                    lastKnownBounds = rect
                }

                // تلاش اول با اکشن کلیک استاندارد، و در صورت عمل نکردن، ضربه فیزیکی روی مختصات کلید
                val clickedByAction = if (attempt == 1) performSmartClick(tileNode) else false
                if (!clickedByAction && lastKnownBounds != null) {
                    tapAtCoordinates(
                        lastKnownBounds.centerX().toFloat(),
                        lastKnownBounds.centerY().toFloat()
                    )
                }
            } else if (lastKnownBounds != null) {
                // اگر پنل هنوز باز است، مستقیماً روی همان مختصات قبلی کلید ضربه می‌زنیم
                tapAtCoordinates(
                    lastKnownBounds.centerX().toFloat(),
                    lastKnownBounds.centerY().toFloat()
                )
            } else {
                // اگر پنل بسته شده بود، دوباره بازش می‌کنیم
                openQuickSettingsPanel()
                continue
            }

            // پایش فوق سریع (هر ۵۰ میلی‌ثانیه) برای تشخیص آنی تغییر وضعیت
            for (poll in 1..25) {
                delay(50)
                if (isAirplaneModeOn() == targetStateOn) {
                    return true
                }
                // اگر بعد از ۳۵۰ میلی‌ثانیه کلیک اول عمل نکرده بود و مختصات داریم، یک ضربه لمسی می‌زنیم
                if (poll == 7 && lastKnownBounds != null && isAirplaneModeOn() != targetStateOn) {
                    tapAtCoordinates(
                        lastKnownBounds.centerX().toFloat(),
                        lastKnownBounds.centerY().toFloat()
                    )
                }
            }
        }

        return isAirplaneModeOn() == targetStateOn
    }

    /**
     * جستجوی کلید حالت هواپیما منحصراً در پنجره سیستم‌عامل (SystemUI)
     * و نادیده گرفتن کامل متن‌های داخل خود برنامه ما (com.seo.autobot)
     */
    private fun findSystemAirplaneTile(): AccessibilityNodeInfo? {
        val keywords = listOf(
            "حالت پرواز",
            "حالت هواپیما",
            "Airplane mode",
            "Airplane",
            "Flight mode",
            "Aeroplane",
            "پرواز"
        )

        val rootNodes = mutableListOf<AccessibilityNodeInfo>()

        // ۱. دریافت پنجره‌های سیستمی (پنل نوتیفیکیشن و Quick Settings)
        try {
            for (win in windows) {
                if (win.type == AccessibilityWindowInfo.TYPE_SYSTEM ||
                    win.type == AccessibilityWindowInfo.TYPE_APPLICATION
                ) {
                    val r = win.root
                    if (r != null && r.packageName?.toString() != packageName) {
                        rootNodes.add(r)
                    }
                }
            }
        } catch (_: Exception) {}

        // ۲. بررسی rootInActiveWindow در صورتی که متعلق به برنامه خودمان نباشد
        val activeRoot = rootInActiveWindow
        if (activeRoot != null && activeRoot.packageName?.toString() != packageName) {
            rootNodes.add(activeRoot)
        }

        for (root in rootNodes) {
            val found = searchNodeTreeForAirplane(root, keywords)
            if (found != null) return found
        }

        return null
    }

    private fun searchNodeTreeForAirplane(
        node: AccessibilityNodeInfo?,
        keywords: List<String>
    ): AccessibilityNodeInfo? {
        if (node == null) return null
        // نادیده گرفتن قطعی هر عنصری که متعلق به پکیج خود برنامه ماست
        if (node.packageName?.toString() == packageName) return null

        val text = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""
        val viewId = node.viewIdResourceName ?: ""

        val matchesKeyword = keywords.any {
            text.contains(it, ignoreCase = true) || desc.contains(it, ignoreCase = true)
        } || viewId.contains("airplane", ignoreCase = true)

        if (matchesKeyword && node.isVisibleToUser) {
            return findClickableTileParent(node)
        }

        for (i in 0 until node.childCount) {
            val childMatch = searchNodeTreeForAirplane(node.getChild(i), keywords)
            if (childMatch != null) return childMatch
        }
        return null
    }

    /**
     * پیدا کردن کادر کلید قابل کلیک (Parent Tile) در پنل Quick Settings
     */
    private fun findClickableTileParent(node: AccessibilityNodeInfo): AccessibilityNodeInfo {
        val screenWidth = resources.displayMetrics.widthPixels
        var current: AccessibilityNodeInfo? = node
        var depth = 0
        while (current != null && depth < 4) {
            val rect = Rect()
            current.getBoundsInScreen(rect)
            // کلید نباید کل عرض صفحه را بگیرد (جلوگیری از انتخاب کل پنل)
            if (current.isClickable && rect.width() in 40..(screenWidth * 2 / 3)) {
                return current
            }
            current = current.parent
            depth++
        }
        return node
    }

    private fun performSmartClick(node: AccessibilityNodeInfo): Boolean {
        if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            return true
        }
        var parent = node.parent
        var steps = 0
        while (parent != null && steps < 3) {
            if (parent.isClickable && parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true
            }
            parent = parent.parent
            steps++
        }
        return false
    }

    private suspend fun closeQuickSettingsSafely() {
        for (i in 1..3) {
            val activePkg = rootInActiveWindow?.packageName?.toString() ?: ""
            val isSystemShadeOpen = try {
                windows.any {
                    it.type == AccessibilityWindowInfo.TYPE_SYSTEM &&
                            it.root?.let { r -> searchNodeTreeForAirplane(r, listOf("حالت پرواز", "حالت هواپیما", "Airplane", "Wi-Fi", "Bluetooth")) != null } == true
                }
            } catch (_: Exception) {
                false
            }

            if (activePkg == packageName && !isSystemShadeOpen) {
                break
            }
            performGlobalAction(GLOBAL_ACTION_BACK)
            delay(400)
        }
    }

    private fun tapAtCoordinates(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 80))
            .build()
        dispatchGesture(gesture, null, null)
    }

    private fun swipeDownQuickSettings() {
        val dm = resources.displayMetrics
        val centerX = dm.widthPixels / 2f
        val startY = dm.heightPixels * 0.10f
        val endY = dm.heightPixels * 0.65f

        val path = Path().apply {
            moveTo(centerX, startY)
            lineTo(centerX, endY)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 280))
            .build()
        dispatchGesture(gesture, null, null)
    }
}
