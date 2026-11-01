package com.yg.vaultbook.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yg.vaultbook.data.VaultGateway
import com.yg.vaultbook.domain.PlainCategory
import kotlinx.coroutines.launch

/**
 * 分类管理。
 *
 * ── 三条规则 ──────────────────────────────────────────────────
 * 1. **内置分类可以改名，但不能删。** "银行卡"改成"卡包"是合理需求；
 *    但删掉内置分类会让"默认分类"这个概念消失，而 seed 逻辑只在空表时跑，
 *    删完之后下次启动也不会补回来。
 * 2. **删分类不删条目。** 先把这个分类下的条目 `category_id` 置空，
 *    它们变成"未分类"，正文一个字都不动。
 * 3. **排序是把 sort 重写成 0..n-1。** 只交换两项的 sort 值也能用，
 *    但历史上删过分类后容易留下空洞与重复值，不如整表重写 ——
 *    分类只有个位数，重写代价可以忽略。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryManageScreen(
    gateway: VaultGateway,
    onBack: () -> Unit
) {
    val repo = remember(gateway) { gateway.repositoryOrNull() }
    val scope = rememberCoroutineScope()

    if (repo == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("保险库未解锁", color = MaterialTheme.colorScheme.error)
        }
        return
    }

    var items by remember { mutableStateOf<List<PlainCategory>>(emptyList()) }
    var counts by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    var loading by remember { mutableStateOf(true) }
    var reload by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    var dialog by remember { mutableStateOf<CategoryDialog?>(null) }
    var confirmDelete by remember { mutableStateOf<PlainCategory?>(null) }

    LaunchedEffect(reload) {
        val list = runCatching { repo.categories() }.getOrDefault(emptyList())
        items = list
        counts = list.associate { c ->
            c.id to runCatching { repo.countEntriesInCategory(c.id) }.getOrDefault(0)
        }
        loading = false
    }

    fun create(name: String) {
        if (busy) return
        busy = true
        scope.launch {
            val id = runCatching { repo.createCategory(name) }.getOrNull()
            busy = false
            message = if (id == null) "名称不能为空" else "已添加「${name.trim()}」"
            reload++
        }
    }

    fun rename(category: PlainCategory, name: String) {
        if (busy) return
        busy = true
        scope.launch {
            val ok = runCatching { repo.renameCategory(category.id, name) }.getOrDefault(false)
            busy = false
            message = if (ok) "已改名" else "改名失败"
            reload++
        }
    }

    fun remove(category: PlainCategory) {
        if (busy) return
        busy = true
        scope.launch {
            val ok = runCatching { repo.deleteCategory(category.id) }.getOrDefault(false)
            busy = false
            message = if (ok) "已删除「${category.name}」，其中条目已转为未分类" else "内置分类不能删除"
            reload++
        }
    }

    fun move(category: PlainCategory, delta: Int) {
        if (busy) return
        busy = true
        scope.launch {
            runCatching { repo.moveCategory(category.id, delta) }
            busy = false
            reload++
        }
    }

    Scaffold(
        topBar = {
            VaultTopBar(
                title = "分类",
                subtitle = "${items.size} 个 · 拖动顺序用箭头调整",
                onBack = onBack
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { dialog = CategoryDialog.Create },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("新建分类") }
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when {
                loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("读取中…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                items.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "还没有分类",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "点右下角新建一个",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                else -> LazyColumn(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    itemsIndexed(items, key = { _, c -> c.id }) { index, category ->
                        CategoryRow(
                            category = category,
                            entryCount = counts[category.id] ?: 0,
                            canMoveUp = index > 0,
                            canMoveDown = index < items.lastIndex,
                            onMoveUp = { move(category, -1) },
                            onMoveDown = { move(category, 1) },
                            onRename = { dialog = CategoryDialog.Rename(category) },
                            onDelete = { confirmDelete = category }
                        )
                    }
                    item {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "名称是加密存储的；条目数只统计，不读取内容。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 4.dp)
                        )
                    }
                }
            }

            message?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 88.dp, start = 24.dp, end = 24.dp)
                )
            }
        }
    }

    when (val target = dialog) {
        null -> Unit

        CategoryDialog.Create -> NameDialog(
            title = "新建分类",
            initial = "",
            confirmText = "添加",
            onDismiss = { dialog = null },
            onConfirm = { name ->
                dialog = null
                create(name)
            }
        )

        is CategoryDialog.Rename -> NameDialog(
            title = "重命名分类",
            initial = target.category.name,
            confirmText = "保存",
            onDismiss = { dialog = null },
            onConfirm = { name ->
                dialog = null
                rename(target.category, name)
            }
        )
    }

    confirmDelete?.let { category ->
        val count = counts[category.id] ?: 0
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("删除「${category.name}」？") },
            text = {
                Text(
                    if (count > 0) {
                        "这个分类下有 $count 条记录，它们会变成「未分类」——条目本身不会被删除。"
                    } else {
                        "这个分类下没有记录，可以直接删除。"
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = null
                    remove(category)
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null }) { Text("取消") }
            }
        )
    }
}

private sealed interface CategoryDialog {
    data object Create : CategoryDialog
    data class Rename(val category: PlainCategory) : CategoryDialog
}

@Composable
private fun CategoryRow(
    category: PlainCategory,
    entryCount: Int,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    val accent = accentOf(category.id.ifEmpty { category.name })
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(Modifier.padding(start = 14.dp, end = 12.dp, top = 12.dp, bottom = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .background(accent.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = category.name.trim().take(1).ifEmpty { "·" },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = accent
                    )
                }
                Spacer(Modifier.width(11.dp))
                Column(
                    Modifier
                        .weight(1f)
                        .clickable(onClick = onRename)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = category.name,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (category.builtin) {
                            Spacer(Modifier.width(6.dp))
                            Box(
                                Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                    .padding(horizontal = 6.dp, vertical = 1.dp)
                            ) {
                                Text(
                                    text = "内置",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    Text(
                        text = "$entryCount 条",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                MoveButton(
                    icon = Icons.Filled.KeyboardArrowUp,
                    description = "上移",
                    enabled = canMoveUp,
                    onClick = onMoveUp
                )
                Spacer(Modifier.width(4.dp))
                MoveButton(
                    icon = Icons.Filled.KeyboardArrowDown,
                    description = "下移",
                    enabled = canMoveDown,
                    onClick = onMoveDown
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                CategoryAction(
                    text = "改名",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    onClick = onRename
                )
                Spacer(Modifier.width(6.dp))
                CategoryAction(
                    text = if (category.builtin) "不可删" else "删除",
                    color = if (category.builtin) {
                        MaterialTheme.colorScheme.outline
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                    enabled = !category.builtin,
                    onClick = onDelete
                )
            }
        }
    }
}

@Composable
private fun MoveButton(
    icon: ImageVector,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(30.dp)
            .clip(CircleShape)
            .background(
                MaterialTheme.colorScheme.surfaceVariant.copy(
                    alpha = if (enabled) 0.70f else 0.28f
                )
            )
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                alpha = if (enabled) 1f else 0.38f
            ),
            modifier = Modifier.size(18.dp)
        )
    }
}

@Composable
private fun CategoryAction(
    text: String,
    color: Color,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(color.copy(alpha = if (enabled) 0.10f else 0.05f))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 5.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = color.copy(alpha = if (enabled) 1f else 0.55f)
        )
    }
}

@Composable
private fun NameDialog(
    title: String,
    initial: String,
    confirmText: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { if (it.length <= 20) text = it },
                singleLine = true,
                label = { Text("名称") },
                supportingText = { Text("${text.length}/20") }
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(text) },
                enabled = text.isNotBlank()
            ) { Text(confirmText) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
