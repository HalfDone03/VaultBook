package com.yg.vaultbook.core.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [EnabledServices] 的行为测试。
 *
 * 这段代码不产生任何用户可见输出，只有布尔值 —— 但它错一次的后果是
 * **设置页显示「已开启」而系统里其实没开**，用户以为能自动填充，
 * 登录时却什么都不发生，还会怀疑是自己手机的问题。
 *
 * 所以这里用各家常 ROM 真实写出来的那几种格式做断言：
 * 单个服务、多个服务用 ':' 连、类名写全称、类名写相对形式（`.Foo`）、
 * 末尾带多余分隔符。这些格式差异全部来自 `Settings.Secure` 的实际取值，
 * 不是假设。注意组件名的规范写法是 `包名/类名`，**斜杠两边都要在**。
 */
class EnabledServicesTest {

    private val pkg = "com.yg.vaultbook"
    private val cls = "$pkg.autofill.VaultAutofillService"

    /** 规范写法：包名 + 斜杠 + 全类名。 */
    private val component = "$pkg/$cls"

    @Test
    fun `单个服务写成全称`() {
        assertTrue(EnabledServices.contains(component, pkg, cls))
    }

    @Test
    fun `采用相对类名写法`() {
        // Android 允许 `<包名>/.ClassName`，多数 ROM 真就这么存
        assertTrue(
            EnabledServices.contains("$pkg/.autofill.VaultAutofillService", pkg, cls)
        )
    }

    @Test
    fun `多个服务用冒号连起来`() {
        val raw = "com.other/.OtherService:$component:com.third/.Third"
        assertTrue(EnabledServices.contains(raw, pkg, cls))
    }

    @Test
    fun `末尾多余冒号不影响`() {
        assertTrue(EnabledServices.contains("$component:", pkg, cls))
        assertTrue(EnabledServices.contains(":$component", pkg, cls))
        // 冒号之间有空串（某些 ROM 清空开关后留下）
        assertTrue(EnabledServices.contains("::$component::", pkg, cls))
    }

    @Test
    fun `本应用不是当前启用的那个`() {
        val raw = "com.other/.OtherService:$pkg/.autofill.SomethingElse"
        assertFalse(EnabledServices.contains(raw, pkg, cls))
    }

    @Test
    fun `别的应用的同名服务不算`() {
        // 类名相同、包名不同：必须是「包名 + 类名」一起对上
        val raw = "com.evil/$cls"
        assertFalse(EnabledServices.contains(raw, pkg, cls))
    }

    @Test
    fun `空值与空白一律没开`() {
        assertFalse(EnabledServices.contains(null, pkg, cls))
        assertFalse(EnabledServices.contains("", pkg, cls))
        assertFalse(EnabledServices.contains("   ", pkg, cls))
        assertFalse(EnabledServices.contains(":", pkg, cls))
        assertFalse(EnabledServices.contains(":::", pkg, cls))
    }

    @Test
    fun `包名为空时直接返回否`() {
        // 防御：拿不到自己的包名说明调用方写错了，不要在这种情况下"猜着算对"
        assertFalse(EnabledServices.contains(component, "", cls))
    }

    @Test
    fun `大小写不敏感`() {
        // 值来自系统设置，理论上规范，但真机上见过被第三方工具改乱的
        assertTrue(
            EnabledServices.contains(
                "com.YG.VaultBook/.AUTOFILL.VaultAutofillService", pkg, cls
            )
        )
    }

    @Test
    fun `前缀相同但类名不同的不算`() {
        // 防「用 startsWith 判断」这类实现把 `...Service2` 也认成 `...Service`
        assertFalse(EnabledServices.contains("$pkg/${cls}2", pkg, cls))
    }

    @Test
    fun `缺斜杠的畸形值不算`() {
        // 只有类名、或斜杠在头尾：都不是合法组件名，不能蒙对
        assertFalse(EnabledServices.contains(cls, pkg, cls))
        assertFalse(EnabledServices.contains(".autofill.VaultAutofillService", pkg, cls))
        assertFalse(EnabledServices.contains("/$cls", pkg, cls))
        assertFalse(EnabledServices.contains("$pkg/", pkg, cls))
    }
}
