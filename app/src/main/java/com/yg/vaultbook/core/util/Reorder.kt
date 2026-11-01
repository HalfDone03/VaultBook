package com.yg.vaultbook.core.util

/**
 * 列表重排 —— 纯函数，可被 JVM 单测直接覆盖。
 *
 * 为什么要单独抽出来：分类的「上移/下移」看着简单，但边界情况很容易写错 ——
 * 第一项再往上移、最后一项再往下移、只有一项、空列表。
 * 这些在 Room 上测不了（需要 instrumentation），抽成纯函数就能毫秒级测完。
 */
object Reorder {

    /**
     * 交换 [from] 与 [to] 两项，返回**新列表**（不修改入参）。
     *
     * 任一下标越界返回 `null` —— 调用方据此判断"已经在头/尾"，
     * 从而不必自己算边界。
     */
    fun <T> swap(items: List<T>, from: Int, to: Int): List<T>? {
        if (from !in items.indices) return null
        if (to !in items.indices) return null
        val copy = items.toMutableList()
        if (from == to) return copy
        val tmp = copy[from]
        copy[from] = copy[to]
        copy[to] = tmp
        return copy
    }
}
