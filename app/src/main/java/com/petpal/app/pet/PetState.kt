package com.petpal.app.pet

/**
 * 宠物状态机 —— 驱动动画和行为切换
 */
enum class PetState {
    /** 待机 —— 轻微呼吸浮动 */
    IDLE,
    /** 走路 —— 在屏幕上移动 */
    WALK,
    /** 开心 —— 被摸头后跳起 */
    HAPPY,
    /** 吃东西 —— 喂零食时 */
    EAT,
    /** 睡觉 —— 长时间不互动 */
    SLEEP;

    /** 该状态每次持续的最短时间（毫秒） */
    val minDuration: Long get() = when (this) {
        IDLE  -> 2000
        WALK  -> 1500
        HAPPY -> 1200
        EAT   -> 2000
        SLEEP -> 5000
    }

    /** 该状态每次持续的最长时间（毫秒） */
    val maxDuration: Long get() = when (this) {
        IDLE  -> 8000
        WALK  -> 3000
        HAPPY -> 2000
        EAT   -> 3000
        SLEEP -> 30000
    }

    /** 是否需要原地播放（不移动） */
    val isStationary: Boolean get() = this != WALK
}
