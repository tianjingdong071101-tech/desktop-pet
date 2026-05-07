package com.petpal.app

import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.petpal.app.pet.PetBehavior
import com.petpal.app.pet.PetState
import com.petpal.app.pet.PetType
import com.petpal.app.ui.MainActivity

/**
 * 悬浮窗桌宠服务 —— 紧凑窗口模式
 *
 * 窗口仅包裹宠物本体，不做全屏覆盖。
 * 触摸只对宠物区域响应，其余区域穿透到底层 App。
 */
class FloatingPetService : Service() {

    // ----- 尺寸常量 (dp → px 实际值在运行时转换) -----
    private var petW = 128
    private var petH = 160  // 包含气泡空间

    // ----- 系统组件 -----
    private lateinit var windowManager: WindowManager
    private var petView: PetView? = null
    private lateinit var notificationManager: NotificationManager

    // ----- 宠物引擎 -----
    private lateinit var behavior: PetBehavior
    private var petType: PetType = PetType.CAT

    // ----- 动画循环 -----
    private var animator: ValueAnimator? = null
    private var lastFrameTime = 0L
    private var screenW = 0
    private var screenH = 0

    // ==================== 生命周期 ====================

    override fun onCreate() {
        super.onCreate()
        running = true
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

        val metrics = windowManager.currentWindowMetrics.bounds
        screenW = metrics.width()
        screenH = metrics.height()

        // dp 转 px
        val density = resources.displayMetrics.density
        petW = (128 * density).toInt()
        petH = (160 * density).toInt()

        val prefs = getSharedPreferences("pet_prefs", MODE_PRIVATE)
        petType = PetType.fromName(prefs.getString("pet_type", "CAT") ?: "CAT")
        behavior = PetBehavior(screenW - petW, screenH - petH)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())

        if (petView == null) {
            petView = PetView(this)
            windowManager.addView(petView, createLayoutParams())
            behavior.init(System.currentTimeMillis())
            startAnimationLoop()
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        running = false
        stopAnimationLoop()
        petView?.let { windowManager.removeView(it) }
        petView = null
        super.onDestroy()
    }

    // ==================== 窗口参数（紧凑模式） ====================

    private fun createLayoutParams(): WindowManager.LayoutParams {
        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        return WindowManager.LayoutParams(
            petW,
            petH,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                    or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                    or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = screenW - petW - 32
            y = screenH / 2
        }
    }

    /** 更新窗口在屏幕上的位置 */
    private fun updateWindowPosition(x: Int, y: Int) {
        val lp = petView?.layoutParams as? WindowManager.LayoutParams ?: return
        lp.x = x.coerceIn(0, screenW - petW)
        lp.y = y.coerceIn(0, screenH - petH)
        windowManager.updateViewLayout(petView, lp)
    }

    // ==================== 动画循环 ====================

    private fun startAnimationLoop() {
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 16
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener {
                val now = System.currentTimeMillis()
                val delta = if (lastFrameTime == 0L) 16 else now - lastFrameTime
                lastFrameTime = now

                behavior.update(delta, now)

                // 走路时同步更新窗口位置
                if (behavior.state == PetState.WALK) {
                    updateWindowPosition(behavior.x.toInt(), behavior.y.toInt())
                }

                petView?.invalidate()
            }
            start()
        }
    }

    private fun stopAnimationLoop() {
        animator?.cancel()
        animator = null
    }

    // ==================== 渲染 View ====================

    private inner class PetView(context: Context) : View(context) {

        private val touchSlop = 12f
        private var touchStartRawX = 0f
        private var touchStartRawY = 0f
        private var winStartX = 0
        private var winStartY = 0
        private var isDragging = false
        private var touchMoved = 0f
        private var frameIndex = 0

        init {
            setWillNotDraw(false)
            setBackgroundColor(Color.TRANSPARENT)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)

            frameIndex = ((System.currentTimeMillis() / 200) % 4).toInt()
            val now = System.currentTimeMillis()

            // 动画偏移（原地动画，窗口不移动）
            val offsetY = when (behavior.state) {
                PetState.IDLE -> {
                    val phase = ((now % 2000) / 2000f * Math.PI * 2).toFloat()
                    kotlin.math.sin(phase) * 6f
                }
                PetState.HAPPY -> {
                    val t = ((now % 400) / 400f * Math.PI).toFloat()
                    kotlin.math.sin(t).toFloat() * 20f
                }
                PetState.EAT -> {
                    val t = ((now % 300) / 300f * Math.PI).toFloat()
                    kotlin.math.sin(t).toFloat() * 10f
                }
                PetState.WALK -> {
                    val t = ((now % 400) / 400f * Math.PI).toFloat()
                    kotlin.math.sin(t).toFloat() * 3f
                }
                PetState.SLEEP -> 0f
            }

            // 宠物绘制在窗口底部中央
            val px = (petW / 2 - 64).toFloat()
            val py = (petH - 128).toFloat() - offsetY

            drawPetSprite(canvas, px, py, behavior.facingRight, behavior.state)

            // 气泡在宠物上方
            if (lastSpeech != null) {
                drawSpeechBubble(canvas, px, py - 16f)
            }
        }

        private fun drawPetSprite(canvas: Canvas, x: Float, y: Float, facingRight: Boolean, state: PetState) {
            canvas.save()
            canvas.translate(x, y)

            val bodyColor = when (petType) {
                PetType.CAT   -> 0xFFFFA726.toInt()
                PetType.DOG   -> 0xFF8D6E63.toInt()
                PetType.BUNNY -> 0xFFF8BBD0.toInt()
            }

            // 身体
            val bodyPaint = Paint().apply { color = bodyColor; isAntiAlias = true }
            val bodyRadius = 32f + petType.scale * 12f
            canvas.drawRoundRect(
                RectF(8f, 20f, 8f + bodyRadius * 2, 20f + bodyRadius * 1.8f),
                bodyRadius, bodyRadius, bodyPaint
            )

            // 耳朵
            val earPath = Path().apply {
                moveTo(16f, 24f); lineTo(8f, -8f); lineTo(36f, 16f); close()
            }
            canvas.drawPath(earPath, bodyPaint)
            earPath.reset()
            earPath.apply {
                moveTo(88f, 24f); lineTo(120f, -8f); lineTo(96f, 16f); close()
            }
            canvas.drawPath(earPath, bodyPaint)

            // 内耳
            val innerEarPaint = Paint().apply { color = 0xFFFFB8C6.toInt(); isAntiAlias = true }
            canvas.drawPath(Path().apply {
                moveTo(19f, 22f); lineTo(13f, 2f); lineTo(30f, 16f); close()
            }, innerEarPaint)
            canvas.drawPath(Path().apply {
                moveTo(91f, 22f); lineTo(115f, 2f); lineTo(98f, 16f); close()
            }, innerEarPaint)

            // 眼睛
            val eyePaint = Paint().apply { color = Color.BLACK; isAntiAlias = true }
            if (state == PetState.SLEEP) {
                eyePaint.strokeWidth = 2.5f
                eyePaint.style = Paint.Style.STROKE
                canvas.drawLine(34f, 44f, 50f, 44f, eyePaint)
                canvas.drawLine(74f, 44f, 90f, 44f, eyePaint)
            } else {
                eyePaint.style = Paint.Style.FILL
                canvas.drawOval(RectF(34f, 38f, 50f, 52f), eyePaint)
                canvas.drawOval(RectF(74f, 38f, 90f, 52f), eyePaint)
            }

            // 高光
            val shineP = Paint().apply { color = Color.WHITE; isAntiAlias = true }
            canvas.drawCircle(43f, 43f, 3.5f, shineP)
            canvas.drawCircle(83f, 43f, 3.5f, shineP)

            // 鼻子
            val noseP = Paint().apply { color = 0xFFFF8A80.toInt(); isAntiAlias = true }
            canvas.drawOval(RectF(58f, 50f, 68f, 56f), noseP)

            // 前爪
            val pawP = Paint().apply { color = 0xFFFFE0B0.toInt(); isAntiAlias = true }
            canvas.drawOval(RectF(12f, 60f, 28f, 70f), pawP)
            canvas.drawOval(RectF(96f, 60f, 112f, 70f), pawP)

            // 尾巴
            val tailP = Paint().apply {
                color = bodyColor; isAntiAlias = true
                style = Paint.Style.STROKE; strokeWidth = 4f; strokeCap = Paint.Cap.ROUND
            }
            val tailPath = Path().apply {
                moveTo(104f, 56f)
                val wag = kotlin.math.sin(System.currentTimeMillis() / 400.0) * 0.5
                quadTo(132f, 56f - 32f, 104f + 36f, 56f - 20f + (wag * 16).toFloat())
            }
            canvas.drawPath(tailPath, tailP)

            canvas.restore()
        }

        private fun drawSpeechBubble(canvas: Canvas, px: Float, py: Float) {
            val text = lastSpeech ?: return
            val textPaint = Paint().apply {
                color = Color.BLACK; textSize = 26f; isAntiAlias = true; isFakeBoldText = true
            }
            val tw = textPaint.measureText(text)
            val bw = tw + 28f
            val bh = 42f
            val bx = px + 64f - bw / 2
            val by = py - bh - 4f

            val bubblePaint = Paint().apply {
                color = 0xEEFFFFFF.toInt(); isAntiAlias = true
                setShadowLayer(3f, 0f, 1f, 0x22000000)
            }
            canvas.drawRoundRect(RectF(bx, by, bx + bw, by + bh), 18f, 18f, bubblePaint)
            canvas.drawPath(Path().apply {
                moveTo(bx + bw / 2 - 7f, by + bh)
                lineTo(bx + bw / 2, by + bh + 10f)
                lineTo(bx + bw / 2 + 7f, by + bh)
                close()
            }, bubblePaint)
            canvas.drawText(text, bx + 14f, by + 29f, textPaint)
        }

        // ==================== 触摸交互 ====================

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    touchStartRawX = event.rawX
                    touchStartRawY = event.rawY
                    val lp = layoutParams as WindowManager.LayoutParams
                    winStartX = lp.x
                    winStartY = lp.y
                    touchMoved = 0f
                    isDragging = false
                    return true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - touchStartRawX
                    val dy = event.rawY - touchStartRawY
                    touchMoved = kotlin.math.abs(dx) + kotlin.math.abs(dy)

                    if (touchMoved > touchSlop) {
                        isDragging = true
                    }
                    if (isDragging) {
                        val newX = winStartX + dx.toInt()
                        val newY = winStartY + dy.toInt()
                        updateWindowPosition(newX, newY)
                        behavior.x = newX.toFloat()
                        behavior.y = newY.toFloat()
                        behavior.targetX = behavior.x
                        behavior.targetY = behavior.y
                    }
                    return true
                }

                MotionEvent.ACTION_UP -> {
                    if (!isDragging && touchMoved < touchSlop) {
                        behavior.onTap(System.currentTimeMillis())
                        showSpeech(randomPhrase())
                    }
                    return true
                }
            }
            return super.onTouchEvent(event)
        }
    }

    // ==================== 对话系统 ====================

    @Volatile
    private var lastSpeech: String? = null
    private var speechHideRunnable: Runnable? = null

    private fun showSpeech(text: String) {
        lastSpeech = text
        speechHideRunnable?.let { mainHandler.removeCallbacks(it) }
        speechHideRunnable = Runnable { lastSpeech = null }
        mainHandler.postDelayed(speechHideRunnable!!, 2000)
    }

    private fun randomPhrase(): String = when (petType) {
        PetType.CAT   -> listOf("喵~", "=^_^=", "呼噜噜~", "摸头!").random()
        PetType.DOG   -> listOf("汪!", "摇尾巴!", "好开心~", "摸摸!").random()
        PetType.BUNNY -> listOf("蹦蹦~", "🐰", "跳跳!", "胡萝卜~").random()
    }

    // ==================== 通知 ====================

    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "pet_overlay"
        private var running = false
        fun isRunning(): Boolean = running
    }

    private val mainHandler get() = android.os.Handler(mainLooper)

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationManager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "桌宠服务", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "桌面宠物运行中"
                }
            )
        }
    }

    private fun buildNotification(): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(com.petpal.app.R.string.pet_running))
            .setContentText(getString(com.petpal.app.R.string.pet_notification))
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }
}
