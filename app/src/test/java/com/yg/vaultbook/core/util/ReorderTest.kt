package com.yg.vaultbook.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReorderTest {

    private val sample = listOf("a", "b", "c", "d", "e")

    @Test
    fun `相邻两项交换`() {
        val result = Reorder.swap(sample, 1, 2)!!
        assertEquals(listOf("a", "c", "b", "d", "e"), result)
    }

    @Test
    fun `首尾交换`() {
        val result = Reorder.swap(sample, 0, 4)!!
        assertEquals(listOf("e", "b", "c", "d", "a"), result)
    }

    @Test
    fun `下移一位就是与下一项交换`() {
        val result = Reorder.swap(sample, 1, 2)!!
        assertEquals("c", result[1])
        assertEquals("b", result[2])
    }

    @Test
    fun `from 越界返回 null`() {
        assertNull(Reorder.swap(sample, -1, 0))
        assertNull(Reorder.swap(sample, 5, 0))
    }

    @Test
    fun `to 越界返回 null 表示已到头或尾`() {
        // 第一项再往上移
        assertNull(Reorder.swap(sample, 0, -1))
        // 最后一项再往下移
        assertNull(Reorder.swap(sample, 4, 5))
    }

    @Test
    fun `from 等于 to 返回等值副本而不是 null`() {
        val result = Reorder.swap(sample, 2, 2)
        assertEquals(sample, result)
    }

    @Test
    fun `不修改入参`() {
        val original = listOf("a", "b", "c")
        Reorder.swap(original, 0, 2)
        assertEquals(listOf("a", "b", "c"), original)
    }

    @Test
    fun `返回的是新列表`() {
        val result = Reorder.swap(sample, 0, 1)!!
        assertTrue(result !== sample)
    }

    @Test
    fun `空列表任何下标都越界`() {
        assertNull(Reorder.swap(emptyList<String>(), 0, 0))
    }

    @Test
    fun `单元素列表不能再移动`() {
        val one = listOf("only")
        assertNull(Reorder.swap(one, 0, 1))
        assertNull(Reorder.swap(one, 0, -1))
        assertEquals(one, Reorder.swap(one, 0, 0))
    }

    @Test
    fun `两步移动等价于先后两次交换`() {
        val once = Reorder.swap(sample, 0, 1)!!
        val twice = Reorder.swap(once, 1, 2)!!
        assertEquals(listOf("b", "c", "a", "d", "e"), twice)
    }
}
