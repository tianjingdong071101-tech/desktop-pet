package com.petpal.app.pet

/**
 * 宠物类型枚举 —— 每种宠物有自己的精灵图和动画帧数
 */
enum class PetType(
    val displayName: String,
    val spriteFolder: String,
    val frameCount: Int,      // 精灵表帧数
    val frameWidth: Int,      // 单帧宽(dp)
    val frameHeight: Int,     // 单帧高(dp)
    val scale: Float = 1f     // 缩放比
) {
    CAT("猫猫", "cat", 8, 128, 128, 1.0f),
    DOG("狗狗", "dog", 6, 128, 128, 1.1f),
    BUNNY("兔子", "bunny", 6, 120, 128, 0.95f);

    companion object {
        fun fromName(name: String): PetType =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: CAT
    }
}
