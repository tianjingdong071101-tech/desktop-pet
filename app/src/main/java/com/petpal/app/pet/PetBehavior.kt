package com.petpal.app.pet

import kotlin.random.Random

/**
 * 宠物行为引擎 —— 管理状态切换、移动路径、互动逻辑
 */
class PetBehavior(
    private val screenWidth: Int,
    private val screenHeight: Int
) {
    var state: PetState = PetState.IDLE
        private set

    var x: Float = screenWidth - 200f
        private set
    var y: Float = screenHeight / 2f
        private set

    var targetX: Float = x
        private set
    var targetY: Float = y
        private set

    var facingRight: Boolean = true
        private set
    var speed: Float = 1.0f  // 0.0 ~ 1.0 速度倍率
    var idleTimeout: Long = 10000  // 多久不动进入待机

    // 内部计时
    private var stateEnterTime: Long = 0
    private var lastInteractionTime: Long = 0
    private var stateDuration: Long = 0

    // 碰撞边界（宠物尺寸）
    private val petWidth = 128
    private val petHeight = 128

    fun init(currentTime: Long) {
        stateEnterTime = currentTime
        lastInteractionTime = currentTime
        scheduleNextState(currentTime)
    }

    /** 每帧更新 */
    fun update(deltaMs: Long, currentTime: Long) {
        when (state) {
            PetState.WALK -> updateWalk(deltaMs)
            PetState.HAPPY -> updateBounce(deltaMs)
            PetState.EAT -> updateBounce(deltaMs)
            PetState.IDLE -> updateIdle(deltaMs)
            PetState.SLEEP -> {} // 不动
        }

        // 状态到期 → 切换
        if (currentTime - stateEnterTime > stateDuration) {
            transitionTo(nextState(currentTime), currentTime)
        }
    }

    /** 用户点击宠物 */
    fun onTap(currentTime: Long) {
        lastInteractionTime = currentTime
        transitionTo(PetState.HAPPY, currentTime)
    }

    /** 用户拖拽宠物 */
    fun onDrag(dx: Float, dy: Float) {
        x = (x + dx).coerceIn(0f, (screenWidth - petWidth).toFloat())
        y = (y + dy).coerceIn(0f, (screenHeight - petHeight).toFloat())
        targetX = x
        targetY = y
    }

    /** 喂食 */
    fun onFeed(currentTime: Long) {
        transitionTo(PetState.EAT, currentTime)
    }

    private fun updateWalk(deltaMs: Long) {
        val step = speed * 0.15f * deltaMs
        val dx = targetX - x
        val dy = targetY - y
        val dist = kotlin.math.sqrt(dx * dx + dy * dy)

        if (dist < step) {
            x = targetX
            y = targetY
        } else {
            x += (dx / dist) * step
            y += (dy / dist) * step
            facingRight = dx > 0
        }
    }

    private fun updateIdle(deltaMs: Long) {
        // 微小呼吸浮动由 renderer 处理
    }

    private fun updateBounce(deltaMs: Long) {
        // 跳动动画由 renderer 处理
    }

    private fun transitionTo(newState: PetState, currentTime: Long) {
        state = newState
        stateEnterTime = currentTime
        stateDuration = Random.nextLong(newState.minDuration, newState.maxDuration)

        if (newState == PetState.WALK) {
            // 随机目标点
            targetX = Random.nextFloat(0f, (screenWidth - petWidth).toFloat())
            targetY = Random.nextFloat(0f, (screenHeight - petHeight).toFloat())
            facingRight = targetX > x
        }
    }

    private fun nextState(currentTime: Long): PetState {
        val idleTime = currentTime - lastInteractionTime
        if (idleTime > 30000) return PetState.SLEEP
        if (idleTime > idleTimeout) return PetState.IDLE
        return if (Random.nextFloat() < 0.6f) PetState.WALK else PetState.IDLE
    }

    private fun scheduleNextState(currentTime: Long) {
        stateDuration = Random.nextLong(state.minDuration, state.maxDuration)
        stateEnterTime = currentTime
    }
}
