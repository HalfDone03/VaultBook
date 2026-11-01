package com.yg.vaultbook.autofill

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 登录字段识别。
 *
 * 这段逻辑决定"哪个框能填密码"。判错的代价极不对称：
 * 把用户名框当密码框，密码会以明文写进可见输入框；反之只是少填一格。
 * 所以所有冲突都以"更像密码"为准。
 */
class AutofillHintsTest {

    @Test
    fun `不可编辑的节点直接忽略`() {
        assertEquals(
            FieldKind.IGNORE,
            AutofillHints.classify(FieldSignals(idEntry = "password", isEditable = false))
        )
    }

    // ── 网站声明的语义最可信 ──────────────────────────────────────

    @Test
    fun `autofillHints声明password`() {
        assertEquals(
            FieldKind.PASSWORD,
            AutofillHints.classify(FieldSignals(autofillHints = listOf("password")))
        )
    }

    @Test
    fun `autofillHints声明newPassword`() {
        assertEquals(
            FieldKind.PASSWORD,
            AutofillHints.classify(FieldSignals(autofillHints = listOf("newPassword")))
        )
    }

    @Test
    fun `autofillHints声明emailAddress`() {
        assertEquals(
            FieldKind.USERNAME,
            AutofillHints.classify(FieldSignals(autofillHints = listOf("emailAddress")))
        )
    }

    @Test
    fun `autofillHints声明phone`() {
        assertEquals(
            FieldKind.USERNAME,
            AutofillHints.classify(FieldSignals(autofillHints = listOf("phoneNumber")))
        )
    }

    @Test
    fun `autofillHints声明name不算账号`() {
        // AUTOFILL_HINT_NAME 指的是"真实姓名"，常见于收货地址表单。
        // 拿它当登录账号会把姓名填进登录框
        assertEquals(
            FieldKind.UNKNOWN,
            AutofillHints.classify(FieldSignals(autofillHints = listOf("name")))
        )
    }

    // ── WebView 的 DOM 属性 ─────────────────────────────────────

    @Test
    fun `htmlType为password`() {
        assertEquals(
            FieldKind.PASSWORD,
            AutofillHints.classify(FieldSignals(htmlAttributes = mapOf("type" to "password")))
        )
    }

    @Test
    fun `htmlType大小写不敏感`() {
        assertEquals(
            FieldKind.PASSWORD,
            AutofillHints.classify(FieldSignals(htmlAttributes = mapOf("type" to "PASSWORD")))
        )
    }

    @Test
    fun `htmlType为email`() {
        assertEquals(
            FieldKind.USERNAME,
            AutofillHints.classify(FieldSignals(htmlAttributes = mapOf("type" to "email")))
        )
    }

    @Test
    fun `htmlType为tel`() {
        assertEquals(
            FieldKind.USERNAME,
            AutofillHints.classify(FieldSignals(htmlAttributes = mapOf("type" to "tel")))
        )
    }

    // ── inputType 的密码变体 ────────────────────────────────────

    @Test
    fun `inputType文本密码变体`() {
        assertEquals(FieldKind.PASSWORD, AutofillHints.classify(FieldSignals(inputType = 0x81)))
    }

    @Test
    fun `inputType可见密码变体`() {
        assertEquals(FieldKind.PASSWORD, AutofillHints.classify(FieldSignals(inputType = 0x91)))
    }

    @Test
    fun `inputType网页密码变体`() {
        assertEquals(FieldKind.PASSWORD, AutofillHints.classify(FieldSignals(inputType = 0xe1)))
    }

    @Test
    fun `inputType数字密码变体`() {
        assertEquals(FieldKind.PASSWORD, AutofillHints.classify(FieldSignals(inputType = 0x12)))
    }

    @Test
    fun `inputType纯文本不是密码`() {
        assertFalse(AutofillHints.isPasswordInputType(0x01))
        assertFalse(AutofillHints.isPasswordInputType(0x00))
    }

    @Test
    fun `数字登录类型不是密码`() {
        // 0x10 在 TEXT 类下是 URI，只有 NUMBER 类下才是密码变体。
        // 只按 variation 判断会把网址输入框当成密码框
        assertFalse(AutofillHints.isPasswordInputType(0x11))
        assertTrue(AutofillHints.isPasswordInputType(0x12))
    }

    // ── 排除项 ──────────────────────────────────────────────────

    @Test
    fun `搜索框被排除`() {
        assertEquals(
            FieldKind.IGNORE,
            AutofillHints.classify(FieldSignals(idEntry = "search_box"))
        )
    }

    @Test
    fun `中文搜索占位符被排除`() {
        assertEquals(
            FieldKind.IGNORE,
            AutofillHints.classify(FieldSignals(htmlAttributes = mapOf("placeholder" to "搜索商品")))
        )
    }

    @Test
    fun `验证码框被排除`() {
        assertEquals(
            FieldKind.IGNORE,
            AutofillHints.classify(FieldSignals(idEntry = "smscode"))
        )
    }

    @Test
    fun `中文验证码提示被排除`() {
        assertEquals(
            FieldKind.IGNORE,
            AutofillHints.classify(FieldSignals(hint = "短信验证码"))
        )
    }

    @Test
    fun `OTP被排除`() {
        assertEquals(
            FieldKind.IGNORE,
            AutofillHints.classify(FieldSignals(idEntry = "otp_input"))
        )
    }

    @Test
    fun `硬证据压过排除关键词`() {
        // 有些站点的验证码框 id 叫 passcode（含 code），但 type=password 时它就是密码框
        assertEquals(
            FieldKind.PASSWORD,
            AutofillHints.classify(
                FieldSignals(
                    idEntry = "passcode",
                    htmlAttributes = mapOf("type" to "password")
                )
            )
        )
    }

    @Test
    fun `密码型inputType也压过排除关键词`() {
        assertEquals(
            FieldKind.PASSWORD,
            AutofillHints.classify(FieldSignals(idEntry = "verifycode", inputType = 0x81))
        )
    }

    // ── 关键词兜底 ──────────────────────────────────────────────

    @Test
    fun `英文密码关键词`() {
        assertEquals(
            FieldKind.PASSWORD,
            AutofillHints.classify(FieldSignals(idEntry = "login_password"))
        )
    }

    @Test
    fun `中文密码关键词`() {
        assertEquals(
            FieldKind.PASSWORD,
            AutofillHints.classify(FieldSignals(htmlAttributes = mapOf("placeholder" to "登录密码")))
        )
    }

    @Test
    fun `英文账号关键词`() {
        assertEquals(
            FieldKind.USERNAME,
            AutofillHints.classify(FieldSignals(idEntry = "username"))
        )
    }

    @Test
    fun `中文账号关键词`() {
        assertEquals(
            FieldKind.USERNAME,
            AutofillHints.classify(FieldSignals(htmlAttributes = mapOf("placeholder" to "手机号")))
        )
    }

    @Test
    fun `同时命中密码和用户名时判为密码`() {
        // `userpassword` 两者都含。判错的代价是把密码明文填进可见框
        assertEquals(
            FieldKind.PASSWORD,
            AutofillHints.classify(FieldSignals(idEntry = "userpassword"))
        )
    }

    @Test
    fun `没有任何线索时返回未知`() {
        assertEquals(FieldKind.UNKNOWN, AutofillHints.classify(FieldSignals()))
    }

    // ── 已填内容不参与判断 ──────────────────────────────────────

    @Test
    fun `value不参与字段判定`() {
        // 用户在登录框里已经输了 "password123"，那只是内容，
        // 不应该让一个普通的文本框被判成密码框
        assertEquals(
            FieldKind.UNKNOWN,
            AutofillHints.classify(
                FieldSignals(htmlAttributes = mapOf("value" to "password123"))
            )
        )
    }
}
