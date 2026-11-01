package com.yg.vaultbook.core.util

import java.util.UUID

/**
 * 主键生成。
 *
 * 用 UUID v4（122 位随机）而不是自增整数，原因有两个：
 * 1. 条目 id 参与字段加密的 AAD（`entryId|fieldName`）。如果 id 是可预测的序号，
 *    攻击者更容易构造出"把 A 的密文搬到 B"的尝试样本。
 * 2. 将来做合并/去重导入时，不同设备的 id 不会撞车。
 *
 * 不用 `UUID.nameUUIDFromBytes` 之类的确定性生成：那会把内容变成 id，
 * 等于把明文信息泄漏进一个明文存储的主键里。
 */
object Ids {
    fun newId(): String = UUID.randomUUID().toString()
}
