package com.petpal.app.pet

import kotlin.random.Random

/**
 * 宠物行为引擎 —— 状态切换、路径规划、互动逻辑
 */
class PetBehavior(
    private val maxX: Int,
    private val maxY: Int
) {
    var state: PetState = PetState.IDLE
        private set

    /** 宠物在屏幕上的位置（窗口跟随此坐标） */
    var x: Float = (maxX - 100).toFloat().coerceAtLeast(0f)
    var y: Float = (maxY / 2).toFloat().coerceAtLeast(0f)

    var targetX: Float = x
    var targetY: Float = y

    var facingRight: Boolean = true
    var speed: Float = 1.0f
    var idleTimeout: Long = 10000

    private var stateEnterTime: Long = 0
    private var lastInteractionTime: Long = 0
    private var stateDuration: Long = 0

    fun init(currentTime: Long) {
        stateEnterTime = currentTime
        lastInteractionTime = currentTime
        stateDuration = Random.nextLong(state.minDuration, state.maxDuration)
    }

    fun update(deltaMs: Long, currentTime: Long) {
        when (state) {
            PetState.WALK -> updateWalk(deltaMs)
            else -> {} // 动画由 renderer 处理
        }

        if (currentTime - stateEnterTime > stateDuration) {
            transitionTo(nextState(currentTime), currentTime)
        }
    }

    fun onTap(currentTime: Long) {
        lastInteractionTime = currentTime
        transitionTo(PetState.HAPPY, currentTime)
    }

    fun onFeed(currentTime: Long) {
        transitionTo(PetState.EAT, currentTime)
    }

    private fun updateWalk(deltaMs: Long) {
        val step = speed * 0.2f * deltaMs
        val dx = targetX - x
        val dy = targetY - y
        val dist = kotlin.math.sqrt(dx * dx + dy * dy)

        if (dist < step) {
            x = targetX
            y = targetY
        } else {
            x = (x + (dx / dist) * step).coerceIn(0f, maxX.toFloat())
            y = (y + (dy / dist) * step).coerceIn(0f, maxY.toFloat())
            facingRight = dx > 0
        }
    }

    private fun transitionTo(newState: PetState, currentTime: Long) {
        state = newState
        stateEnterTime = currentTime
        stateDuration = Random.nextLong(newState.minDuration, newState.maxDuration)

        if (newState == PetState.WALK) {
            targetX = Random.nextFloat() * maxX
            targetY = Random.nextFloat() * maxY
            facingRight = targetX > x
        }
    }

    private fun nextState(currentTime: Long): PetState {
        val idleTime = currentTime - lastInteractionTime
        if (idleTime > 30000) return PetState.SLEEP
        if (idleTime > idleTimeout) return PetState.IDLE
        return if (Random.nextFloat() < 0.55f) PetState.WALK else PetState.IDLE
    }
}
