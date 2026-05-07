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
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.content.res.ResourcesCompat
import com.petpal.app.pet.PetBehavior
import com.petpal.app.pet.PetState
import com.petpal.app.pet.PetType
import com.petpal.app.ui.MainActivity
import kotlin.math.roundToInt

/**
 * 悬浮窗桌宠服务 —— 紧凑窗口 + 精灵图渲染
 */
class FloatingPetService : Service() {

    // ----- 尺寸 -----
    private var petW = 0
    private var petH = 0
    private val spriteSize = 108  // dp: 精灵图显示尺寸 (180 * 0.6)

    // ----- 系统组件 -----
    private lateinit var windowManager: WindowManager
    private var petView: PetView? = null
    private lateinit var notificationManager: NotificationManager

    // ----- 精灵图 -----
    private var spriteIdle1: Bitmap? = null
    private var spriteIdle2: Bitmap? = null
    private var spriteWalk1: Bitmap? = null

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

        val density = resources.displayMetrics.density
        petW = (spriteSize * density).roundToInt()
        petH = (spriteSize * density).roundToInt()

        // 加载精灵图
        loadSprites()

        val prefs = getSharedPreferences("pet_prefs", MODE_PRIVATE)
        petType = PetType.fromName(prefs.getString("pet_type", "CAT") ?: "CAT")
        behavior = PetBehavior(screenW - petW, screenH - petH)
    }

    private fun loadSprites() {
        val opts = BitmapFactory.Options().apply { inScaled = false }
        spriteIdle1 = loadAndScale(R.drawable.sprite_idle_1, opts)
        spriteIdle2 = loadAndScale(R.drawable.sprite_idle_2, opts)
        spriteWalk1 = loadAndScale(R.drawable.sprite_walk_1, opts)
    }

    private fun loadAndScale(resId: Int, opts: BitmapFactory.Options): Bitmap? {
        val original = BitmapFactory.decodeResource(resources, resId, opts) ?: return null
        val scaled = Bitmap.createScaledBitmap(original, petW, petH, true)
        if (scaled != original) original.recycle()
        return scaled
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
        spriteIdle1?.recycle()
        spriteIdle2?.recycle()
        spriteWalk1?.recycle()
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

            val now = System.currentTimeMillis()

            // 原地动画偏移
            val offsetY = when (behavior.state) {
                PetState.IDLE -> {
                    val phase = ((now % 2000) / 2000f * Math.PI * 2).toFloat()
                    kotlin.math.sin(phase) * 4f
                }
                PetState.HAPPY -> {
                    val t = ((now % 400) / 400f * Math.PI).toFloat()
                    kotlin.math.sin(t).toFloat() * 16f
                }
                PetState.EAT -> {
                    val t = ((now % 350) / 350f * Math.PI).toFloat()
                    kotlin.math.sin(t).toFloat() * 8f
                }
                PetState.WALK -> {
                    val t = ((now % 350) / 350f * Math.PI).toFloat()
                    kotlin.math.sin(t).toFloat() * 5f
                }
                PetState.SLEEP -> 0f
            }

            // 选帧
            val sprite = when (behavior.state) {
                PetState.IDLE -> if ((now / 800) % 2 == 0L) spriteIdle1 else spriteIdle2
                PetState.WALK -> spriteWalk1
                PetState.HAPPY -> spriteIdle1
                PetState.SLEEP -> spriteIdle2
                PetState.EAT -> spriteIdle1
            }

            // 绘制精灵图（填满整个窗口）
            sprite?.let { bmp ->
                if (behavior.facingRight) {
                    canvas.drawBitmap(bmp, 0f, offsetY, null)
                } else {
                    canvas.save()
                    canvas.scale(-1f, 1f, petW / 2f, petH / 2f)
                    canvas.drawBitmap(bmp, 0f, offsetY, null)
                    canvas.restore()
                }
            }

            // 气泡
            if (lastSpeech != null) {
                drawSpeechBubble(canvas, 0f, -24f)
            }
        }

        private fun drawSpeechBubble(canvas: Canvas, px: Float, py: Float) {
            val text = lastSpeech ?: return
            val textPaint = Paint().apply {
                color = Color.BLACK; textSize = 26f; isAntiAlias = true; isFakeBoldText = true
            }
            val tw = textPaint.measureText(text)
            val bw = tw + 28f
            val bh = 42f
            val bx = px + petW / 2f - bw / 2
            val by = py - bh

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

    private fun randomPhrase(): String = listOf(
        "墨君在此", "何事唤我?", "清风拂面~", "竹简里有秘密",
        "唔...", "好梦正酣", "今日宜静心", "江湖路远且慢行"
    ).random()

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
