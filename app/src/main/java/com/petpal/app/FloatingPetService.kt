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
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.petpal.app.pet.PetBehavior
import com.petpal.app.pet.PetState
import com.petpal.app.pet.PetType
import com.petpal.app.ui.MainActivity
import java.io.File

/**
 * 悬浮窗桌宠服务 —— 整个 App 的核心
 *
 * 使用 WindowManager 创建一个全屏透明 SurfaceView，
 * 在上面绘制宠物动画。用户可拖拽、点击与宠物交互。
 */
class FloatingPetService : Service() {

    // ----- 系统组件 -----
    private lateinit var windowManager: WindowManager
    private var petView: PetSurfaceView? = null
    private lateinit var notificationManager: NotificationManager

    // ----- 宠物引擎 -----
    private lateinit var behavior: PetBehavior
    private var petType: PetType = PetType.CAT

    // ----- 动画循环 -----
    private var animator: ValueAnimator? = null
    private var lastFrameTime = 0L

    // ----- 渲染用 -----
    private val bgPaint = Paint().apply { color = Color.TRANSPARENT }
    private val spriteBitmap = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
    private val spriteCanvas = Canvas(spriteBitmap)
    private var frameIndex = 0
    private var bounceOffset = 0f
    private var breathPhase = 0f

    // ==================== 生命周期 ====================

    override fun onCreate() {
        super.onCreate()
        running = true
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

        // 读取用户设置
        val prefs = getSharedPreferences("pet_prefs", MODE_PRIVATE)
        petType = PetType.fromName(prefs.getString("pet_type", "CAT") ?: "CAT")

        val screenW = windowManager.currentWindowMetrics.bounds.width()
        val screenH = windowManager.currentWindowMetrics.bounds.height()
        behavior = PetBehavior(screenW, screenH)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())

        if (petView == null) {
            petView = PetSurfaceView(this)
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

    // ==================== 窗口参数 ====================

    private fun createLayoutParams(): WindowManager.LayoutParams {
        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                    or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }
    }

    // ==================== 动画循环 ====================

    private fun startAnimationLoop() {
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 16  // ~60 FPS
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { animation ->
                val now = System.currentTimeMillis()
                val delta = if (lastFrameTime == 0L) 16 else now - lastFrameTime
                lastFrameTime = now

                behavior.update(delta, now)
                petView?.invalidate()
            }
            start()
        }
    }

    private fun stopAnimationLoop() {
        animator?.cancel()
        animator = null
    }

    // ==================== 渲染 ====================

    /**
     * 内嵌 SurfaceView —— 绘制宠物到透明窗口
     */
    private inner class PetSurfaceView(context: Context) : View(context) {

        private val touchSlop = 20f
        private var touchStartX = 0f
        private var touchStartY = 0f
        private var petStartX = 0f
        private var petStartY = 0f
        private var isDragging = false
        private var touchMoved = 0f

        init {
            setWillNotDraw(false)
            setBackgroundColor(Color.TRANSPARENT)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)

            // 更新帧动画
            frameIndex = ((System.currentTimeMillis() / 200) % 4).toInt()

            // 根据状态计算偏移
            val now = System.currentTimeMillis()
            val offsetY = when (behavior.state) {
                PetState.IDLE -> {
                    breathPhase = ((now % 2000) / 2000f * Math.PI * 2).toFloat()
                    kotlin.math.sin(breathPhase) * 6f
                }
                PetState.HAPPY -> {
                    val t = ((now % 400) / 400f * Math.PI).toFloat()
                    kotlin.math.sin(t).toFloat() * 25f
                }
                PetState.EAT -> {
                    val t = ((now % 300) / 300f * Math.PI).toFloat()
                    kotlin.math.sin(t).toFloat() * 12f
                }
                PetState.WALK -> {
                    val t = ((now % 400) / 400f * Math.PI).toFloat()
                    kotlin.math.sin(t).toFloat() * 4f
                }
                PetState.SLEEP -> 0f
            }

            // 绘制精灵图（目前用程序化图形代替）
            drawPetSprite(canvas, behavior.x, behavior.y - offsetY, behavior.facingRight, behavior.state)

            // 对话气泡
            drawSpeechBubble(canvas, behavior.x, behavior.y - 100f)
        }

        /** 程序化绘制宠物（后期可替换为精灵图 PNG） */
        private fun drawPetSprite(canvas: Canvas, x: Float, y: Float, facingRight: Boolean, state: PetState) {
            canvas.save()
            canvas.translate(x, y)

            val bodyColor = when (petType) {
                PetType.CAT   -> 0xFFFFA726.toInt()
                PetType.DOG   -> 0xFF8D6E63.toInt()
                PetType.BUNNY -> 0xFFF8BBD0.toInt()
            }

            // === 身体 ===
            val bodyPaint = Paint().apply {
                color = bodyColor
                isAntiAlias = true
            }
            val bodyRadius = 32f + petType.scale * 16f
            canvas.drawRoundRect(
                RectF(8f, 20f, 8f + bodyRadius * 2, 20f + bodyRadius * 1.8f),
                bodyRadius, bodyRadius, bodyPaint
            )

            // === 耳朵 ===
            val earPaint = Paint().apply {
                color = bodyColor
                isAntiAlias = true
                style = Paint.Style.FILL
            }
            val earPath = Path()
            earPath.moveTo(16f, 24f)
            earPath.lineTo(8f, -8f)
            earPath.lineTo(36f, 16f)
            earPath.close()
            canvas.drawPath(earPath, earPaint)
            earPath.reset()
            earPath.moveTo(88f, 24f)
            earPath.lineTo(120f, -8f)
            earPath.lineTo(96f, 16f)
            earPath.close()
            canvas.drawPath(earPath, earPaint)

            // === 眼睛 ===
            val eyePaint = Paint().apply {
                color = Color.BLACK
                isAntiAlias = true
            }
            if (state == PetState.SLEEP) {
                // 闭眼 = 两条线
                eyePaint.strokeWidth = 2f
                eyePaint.style = Paint.Style.STROKE
                canvas.drawLine(36f, 44f, 48f, 44f, eyePaint)
                canvas.drawLine(76f, 44f, 88f, 44f, eyePaint)
            } else {
                // 睁眼 = 椭圆
                eyePaint.style = Paint.Style.FILL
                canvas.drawOval(RectF(34f, 38f, 50f, 52f), eyePaint)
                canvas.drawOval(RectF(74f, 38f, 90f, 52f), eyePaint)
            }

            // 高光
            val shinePaint = Paint().apply { color = Color.WHITE; isAntiAlias = true }
            canvas.drawCircle(43f, 43f, 3f, shinePaint)
            canvas.drawCircle(83f, 43f, 3f, shinePaint)

            // === 嘴鼻 ===
            val nosePaint = Paint().apply { color = 0xFFFF8A80.toInt(); isAntiAlias = true }
            canvas.drawOval(RectF(58f, 50f, 68f, 56f), nosePaint)

            // 尾巴（猫/狗不同）
            val tailPaint = Paint().apply {
                color = bodyColor
                isAntiAlias = true
                style = Paint.Style.STROKE
                strokeWidth = 5f
                strokeCap = Paint.Cap.ROUND
            }
            val tailPath = Path()
            val tailBaseX = 104f
            val tailBaseY = 56f
            tailPath.moveTo(tailBaseX, tailBaseY)
            val wagAngle = kotlin.math.sin(System.currentTimeMillis() / 400.0) * 0.5
            val tailEndX = tailBaseX + 36f
            val tailEndY = tailBaseY - 20f + (wagAngle * 16).toFloat()
            tailPath.quadTo(tailBaseX + 24f, tailBaseY - 32f, tailEndX, tailEndY)
            canvas.drawPath(tailPath, tailPaint)

            canvas.restore()
        }

        private fun drawSpeechBubble(canvas: Canvas, px: Float, py: Float) {
            // 说话气泡（在特定状态显示）
            val text = lastSpeech
            if (text == null) return

            val textPaint = Paint().apply {
                color = Color.BLACK
                textSize = 28f
                isAntiAlias = true
                isFakeBoldText = true
            }
            val textWidth = textPaint.measureText(text)
            val bubbleW = textWidth + 32f
            val bubbleH = 48f
            val bubbleX = px + 64f - bubbleW / 2
            val bubbleY = py - bubbleH - 8f

            // 背景
            val bubblePaint = Paint().apply {
                color = 0xEEFFFFFF.toInt()
                isAntiAlias = true
                setShadowLayer(4f, 0f, 2f, 0x22000000)
            }
            canvas.drawRoundRect(RectF(bubbleX, bubbleY, bubbleX + bubbleW, bubbleY + bubbleH), 20f, 20f, bubblePaint)

            // 三角
            val triPath = Path()
            triPath.moveTo(bubbleX + bubbleW / 2 - 8f, bubbleY + bubbleH)
            triPath.lineTo(bubbleX + bubbleW / 2, bubbleY + bubbleH + 12f)
            triPath.lineTo(bubbleX + bubbleW / 2 + 8f, bubbleY + bubbleH)
            triPath.close()
            canvas.drawPath(triPath, bubblePaint)

            canvas.drawText(text, bubbleX + 16f, bubbleY + 32f, textPaint)
        }

        // ==================== 触摸交互 ====================

        override fun onTouchEvent(event: MotionEvent): Boolean {
            val px = behavior.x
            val py = behavior.y
            val petW = 128f
            val petH = 128f

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    touchStartX = event.rawX
                    touchStartY = event.rawY
                    petStartX = px
                    petStartY = py
                    touchMoved = 0f
                    isDragging = false

                    // 判断是否点中了宠物
                    if (event.rawX < px || event.rawX > px + petW ||
                        event.rawY < py || event.rawY > py + petH) {
                        return false // 没点到宠物，穿透触摸
                    }
                    return true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - touchStartX
                    val dy = event.rawY - touchStartY
                    touchMoved = kotlin.math.abs(dx) + kotlin.math.abs(dy)

                    if (touchMoved > touchSlop) {
                        isDragging = true
                    }
                    if (isDragging) {
                        behavior.onDrag(
                            event.rawX - touchStartX,
                            event.rawY - touchStartY
                        )
                        touchStartX = event.rawX
                        touchStartY = event.rawY
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
        speechHideRunnable?.let { getMainThreadHandler().removeCallbacks(it) }
        speechHideRunnable = Runnable { lastSpeech = null }
        getMainThreadHandler().postDelayed(speechHideRunnable!!, 2000)
    }

    private fun randomPhrase(): String = when (petType) {
        PetType.CAT   -> listOf("喵~", "=^_^=", "呼噜噜~", "摸头!").random()
        PetType.DOG   -> listOf("汪!", "摇尾巴!", "好开心~", "再摸摸!").random()
        PetType.BUNNY -> listOf("蹦蹦~", "🐰", "胡萝卜呢?", "跳跳!").random()
    }

    // ==================== 通知 ====================

    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "pet_overlay"
        private var running = false
        fun isRunning(): Boolean = running
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "桌宠服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "桌面宠物运行中"
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(com.petpal.app.R.string.pet_running))
            .setContentText(getString(com.petpal.app.R.string.pet_notification))
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun getMainThreadHandler() = android.os.Handler(mainLooper)
}
