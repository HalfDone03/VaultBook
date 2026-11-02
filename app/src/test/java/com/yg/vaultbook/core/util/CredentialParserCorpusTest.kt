package com.yg.vaultbook.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 粘贴样本表：写清"这段文字应该被认成什么"，任何一条对不上就红。
 *
 * 这张表的作用与 `CredentialParserFormatsTest`、`CredentialParserStructuredTest` 不同：
 * 那两个按"规则"分组，这张按"手上真会粘到的东西"分组 —— 从 Excel 复制的表、
 * 记事本里抄的三行、聊天记录里转发的卡片、`.env` 文件、浏览器地址栏的登录链接。
 * 先把期望值写死，再让解析器去够，够不到就是缺规则。
 *
 * 期望值全部是编造的假数据，真实样本只描述形状。
 *
 * 一条用例失败不影响其他用例：所有不符先收集起来，最后一次性报出，
 * 免得"改一条、跑一遍、发现下一条"。
 */
class CredentialParserCorpusTest {

    private data class Row(
        val title: String? = null,
        val user: String? = null,
        val pass: String? = null,
        val notes: String? = null
    )

    private data class Case(val name: String, val text: String, val expect: List<Row>)

    private val cases: List<Case> = listOf(
        // ── 一、键值对：一行一个字段 ─────────────────────────────
        Case(
            "每行一个字段",
            "网站：淘宝\n账号：taobao_01\n密码：Tb@123456",
            listOf(Row("淘宝", "taobao_01", "Tb@123456"))
        ),
        Case(
            "同一行三个键值对",
            "平台: B站 用户名: bili_02 密码: Bili#654321",
            listOf(Row("B站", "bili_02", "Bili#654321"))
        ),
        Case(
            "带备注的四个字段",
            "平台：京东\n账号：jd_02\n密码：Jd#654321\n备注：备用",
            listOf(Row("京东", "jd_02", "Jd#654321", "备用"))
        ),
        Case(
            "英文键名",
            "Site: Steam\nUsername: steam_03\nPassword: St#123456",
            listOf(Row("Steam", "steam_03", "St#123456"))
        ),
        Case(
            "环境变量写法",
            "SITE=github\nUSER=gh_04\nPASS=Gh#654321",
            listOf(Row("github", "gh_04", "Gh#654321"))
        ),
        Case(
            "大写等号加空格",
            "平台 = 知乎\n账号 = zhihu_05\n密码 = Zh@555555",
            listOf(Row("知乎", "zhihu_05", "Zh@555555"))
        ),

        // ── 二、列表与项目符号 ───────────────────────────────────
        Case(
            "短横线列表",
            "- 淘宝 taobao_06 Tb@666666",
            listOf(Row("淘宝", "taobao_06", "Tb@666666"))
        ),
        Case(
            "数字加右括号",
            "1) 微博 weibo_07 Wb@777777",
            listOf(Row("微博", "weibo_07", "Wb@777777"))
        ),
        Case(
            "圆点列表",
            "• 知乎 zhihu_08 Zh#888888",
            listOf(Row("知乎", "zhihu_08", "Zh#888888"))
        ),
        Case(
            "中方块序号",
            "① 豆瓣 douban_09 Db@999999",
            listOf(Row("豆瓣", "douban_09", "Db@999999"))
        ),

        // ── 三、表格 ─────────────────────────────────────────────
        Case(
            "逗号分隔带表头",
            "平台,账号,密码\n淘宝,a1,Tb@111111\n京东,a2,Jd@222222",
            listOf(Row("淘宝", "a1", "Tb@111111"), Row("京东", "a2", "Jd@222222"))
        ),
        Case(
            "制表符分隔带备注列",
            "平台\t账号\t密码\t备注\n淘宝\tb1\tTb@333333\t备用",
            listOf(Row("淘宝", "b1", "Tb@333333", "备用"))
        ),
        Case(
            "竖线分隔带网址列",
            "网站|用户名|密码|网址\n淘宝|c1|Tb@444444|taobao.com",
            listOf(Row("淘宝", "c1", "Tb@444444"))
        ),
        Case(
            "带序号列的表格",
            "序号,平台,账号,密码\n1,淘宝,k1,Tb@111111\n2,京东,k2,Jd@222222",
            listOf(Row("淘宝", "k1", "Tb@111111"), Row("京东", "k2", "Jd@222222"))
        ),
        Case(
            "分号分隔无表头",
            "淘宝;l1;Tb@111111\n京东;l2;Jd@222222",
            listOf(Row("淘宝", "l1", "Tb@111111"), Row("京东", "l2", "Jd@222222"))
        ),

        // ── 四、JSON ────────────────────────────────────────────
        Case(
            "JSON 数组两条",
            """[{"site":"Steam","user":"d1","pass":"St#111111"},{"site":"Epic","user":"d2","pass":"Ep#222222"}]""",
            listOf(Row("Steam", "d1", "St#111111"), Row("Epic", "d2", "Ep#222222"))
        ),
        Case(
            "JSON 外面套数据层",
            """{"data":{"list":[{"name":"淘宝","account":"e1","password":"E@111111"},{"name":"京东","account":"e2","password":"E@222222"}]}}""",
            listOf(Row("淘宝", "e1", "E@111111"), Row("京东", "e2", "E@222222"))
        ),
        Case(
            "JSON 只有一条且多层嵌套",
            """{"data":{"item":{"name":"淘宝","account":"f1","password":"F#111111"}}}""",
            listOf(Row("淘宝", "f1", "F#111111"))
        ),
        Case(
            "JSON 数字型密码",
            """[{"name":"淘宝","account":"g1","password":12345678},{"name":"京东","account":"g2","password":87654321}]""",
            listOf(Row("淘宝", "g1", "12345678"), Row("京东", "g2", "87654321"))
        ),
        Case(
            "JSON 密码里有转义引号",
            """[{"name":"淘宝","account":"h1","password":"H\"pa55"},{"name":"京东","account":"h2","password":"H\"pa66"}]""",
            listOf(Row("淘宝", "h1", "H\"pa55"), Row("京东", "h2", "H\"pa66"))
        ),

        // ── 五、XML ─────────────────────────────────────────────
        Case(
            "XML 单条",
            "<account><name>淘宝</name><user>i1</user><password>I#111111</password></account>",
            listOf(Row("淘宝", "i1", "I#111111"))
        ),
        Case(
            "XML 属性写法",
            """<entry site="淘宝" user="j1" pass="J#111111"/><entry site="京东" user="j2" pass="J#222222"/>""",
            listOf(Row("淘宝", "j1", "J#111111"), Row("京东", "j2", "J#222222"))
        ),
        Case(
            "XML 带一层包裹",
            "<list><item><platform>淘宝</platform><account>k1</account><password>K#111111</password></item>" +
                "<item><platform>京东</platform><account>k2</account><password>K#222222</password></item></list>",
            listOf(Row("淘宝", "k1", "K#111111"), Row("京东", "k2", "K#222222"))
        ),

        // ── 六、网址与查询串 ────────────────────────────────────
        Case(
            "网址里的查询串",
            "https://shop.example.com/login?user=abc&pwd=xy@123",
            listOf(Row(null, "abc", "xy@123"))
        ),
        Case(
            "链接里带账号密码",
            "https://demo:Pa55@mail.example.com/inbox",
            listOf(Row(null, "demo", "Pa55"))
        ),
        Case(
            "网址后面接中文键",
            "https://a.example.com:8443 账号 admin 密码 Admin@123",
            listOf(Row(null, "admin", "Admin@123"))
        ),

        // ── 七、中文句子 ────────────────────────────────────────
        Case(
            "句子里夹账号密码",
            "淘宝的账号是 taobao_12 密码是 Tb@121212",
            listOf(Row("淘宝", "taobao_12", "Tb@121212"))
        ),
        Case(
            "账号密码连写",
            "微博账号weibo_13密码Wb@131313",
            listOf(Row("微博", "weibo_13", "Wb@131313"))
        ),
        Case(
            "书名号包平台名",
            "「淘宝」账号 taobao_14 密码 Tb@141414",
            listOf(Row("淘宝", "taobao_14", "Tb@141414"))
        ),
        Case(
            "方头括号转发卡片",
            "【淘宝】\n账号：taobao_15\n密码：Tb@151515",
            listOf(Row("淘宝", "taobao_15", "Tb@151515"))
        ),

        // ── 八、上下交替与分段 ──────────────────────────────────
        Case(
            "账号密码交替三组",
            "j1@example.com\nJa@111111\nj2@example.com\nJa#222222\nj3@example.com\nJa\$333333",
            listOf(
                Row(null, "j1@example.com", "Ja@111111"),
                Row(null, "j2@example.com", "Ja#222222"),
                Row(null, "j3@example.com", "Ja\$333333")
            )
        ),
        Case(
            "标题加一组交替",
            "邮箱\nm1@example.com\nMa@111111",
            listOf(Row("邮箱", "m1@example.com", "Ma@111111"))
        ),
        Case(
            "空行分段两组",
            "淘宝\n账号 a1\n密码 p1\n\n京东\n账号 a2\n密码 p2",
            listOf(Row("淘宝", "a1", "p1"), Row("京东", "a2", "p2"))
        ),

        // ── 九、残缺与边角 ──────────────────────────────────────
        Case(
            "只有密码没有账号",
            "淘宝\n密码：Tb@161616",
            listOf(Row("淘宝", null, "Tb@161616"))
        ),
        Case(
            "手机号就是账号",
            "手机号：13800000009 密码：Abc@12345",
            listOf(Row(null, "13800000009", "Abc@12345"))
        ),
        Case(
            "路由器四行",
            "路由器\n地址：192.168.1.1\n账号：admin\n密码：Admin@123",
            listOf(Row("路由器", "admin", "Admin@123"))
        ),
        Case(
            "全角等号",
            "账号＝a1 密码＝p1",
            listOf(Row(null, "a1", "p1"))
        ),
        Case(
            "备注里带冒号",
            "账号 a1 密码 p1 备注：验证码存在邮箱里",
            listOf(Row(null, "a1", "p1", "验证码存在邮箱里"))
        ),
        Case(
            "同行重复出现两组键",
            "平台：淘宝 账号：n1 密码：p1 平台：京东 账号：n2 密码：p2",
            listOf(Row("淘宝", "n1", "p1"), Row("京东", "n2", "p2"))
        ),

        // ── 十、从别的软件里复制出来的 ───────────────────────────
        Case(
            "yaml 列表",
            "- platform: Steam\n  username: yaml_u\n  password: Ya@123456",
            listOf(Row("Steam", "yaml_u", "Ya@123456"))
        ),
        Case(
            "密码用引号包住且有空格",
            "账号：q1 密码：\"P@ss word\"",
            listOf(Row(null, "q1", "P@ss word"))
        ),
        Case(
            "windows 换行",
            "账号：w1\r\n密码：Wp@123456",
            listOf(Row(null, "w1", "Wp@123456"))
        ),
        Case(
            "制表符两列没表头",
            "tb_1\tTb@123456\ntb_2\tTb@654321",
            listOf(Row(null, "tb_1", "Tb@123456"), Row(null, "tb_2", "Tb@654321"))
        ),
        Case(
            "csv 里引号包住的逗号",
            "账号,密码\n\"a,1\",\"p,1\"",
            listOf(Row(null, "a,1", "p,1"))
        ),
        Case(
            "csv 文件带 bom",
            "\uFEFF平台,账号,密码\n淘宝,b1,Tb@111111",
            listOf(Row("淘宝", "b1", "Tb@111111"))
        ),
        Case(
            "一行四个字段",
            "账号：a9 密码：p9 备注：n9 网址：www.a9.com",
            listOf(Row(null, "a9", "p9", "n9"))
        ),
        Case(
            "分隔线隔开两组",
            "-----\n淘宝\ntb_x\nTb@123456\n-----\n京东\njd_x\nJd@654321",
            listOf(Row("淘宝", "tb_x", "Tb@123456"), Row("京东", "jd_x", "Jd@654321"))
        ),
        Case(
            "全角空格当分隔",
            "平台　知乎　账号　zh_1　密码　Zh@123456",
            listOf(Row("知乎", "zh_1", "Zh@123456"))
        ),
        Case(
            "序号加连写",
            "1. 淘宝taobao_20 Tb@202020",
            listOf(Row("淘宝", "taobao_20", "Tb@202020"))
        ),
        Case(
            "微信导出三个字段",
            "昵称：wx_nick\n微信号：wx_id01\n密码：Wx@112233",
            listOf(Row(null, "wx_id01", "Wx@112233"))
        ),
        Case(
            "口令是老写法",
            "用户名：u1\n口令：K@k12345",
            listOf(Row(null, "u1", "K@k12345"))
        ),
        Case(
            "带端口的登录链接",
            "https://ex.com:8443/login 账号：p1x 密码：Px@12345",
            listOf(Row(null, "p1x", "Px@12345"))
        ),
        Case(
            "json 里带转义引号",
            """{"site":"淘宝","user":"j_1","pass":"J\"1@2"}""",
            listOf(Row("淘宝", "j_1", "J\"1@2"))
        ),
        Case(
            "xml 属性用单引号",
            "<e site='淘宝' user='x1' pass='X@111111'/>",
            listOf(Row("淘宝", "x1", "X@111111"))
        ),
        Case(
            "平台名带表情",
            "平台：抖音🎵\n账号：dy_1\n密码：Dy@123456",
            listOf(Row("抖音🎵", "dy_1", "Dy@123456"))
        ),
        Case(
            "两组之间空一行且各自带标题",
            "淘宝\n账号：a1x\n密码：Ab@111111\n\n京东\n账号：c1x\n密码：Cd@222222",
            listOf(Row("淘宝", "a1x", "Ab@111111"), Row("京东", "c1x", "Cd@222222"))
        ),
        Case(
            "前面有一句说明",
            "账号密码如下：\n淘宝\n账号：taobao_30\n密码：Tb@303030",
            listOf(Row("淘宝", "taobao_30", "Tb@303030"))
        ),
        Case(
            "密码后面跟句号",
            "账号：e1 密码：E@123456。",
            listOf(Row(null, "e1", "E@123456"))
        ),

        // ── 十一、更刁的写法 ─────────────────────────────────────
        Case(
            "没有冒号只有空格",
            "平台 淘宝 账号 tu_1 密码 Tu@111111",
            listOf(Row("淘宝", "tu_1", "Tu@111111"))
        ),
        Case(
            "密码里有等号",
            "账号：eq1 密码：Ab=12Cd",
            listOf(Row(null, "eq1", "Ab=12Cd"))
        ),
        Case(
            "平台名后紧跟冒号对",
            "淘宝 admin1:Pa@ss123",
            listOf(Row("淘宝", "admin1", "Pa@ss123"))
        ),
        Case(
            "前面一行是登录信息",
            "登录信息：\n账号：lg1\n密码：Lg@123456",
            listOf(Row(null, "lg1", "Lg@123456"))
        ),
        Case(
            "json 键名不加引号",
            "{site: Steam, user: noj_1, pass: Nj@111111}",
            listOf(Row("Steam", "noj_1", "Nj@111111"))
        ),
        Case(
            "密码确认行不覆盖密码",
            "账号：dp1\n密码：Dp@111111\n密码确认：Dp@111111",
            listOf(Row(null, "dp1", "Dp@111111"))
        ),
        Case(
            "手机号单独一栏",
            "账号：ph1 密码：Ph@111111 手机：13800000000",
            listOf(Row(null, "ph1", "Ph@111111"))
        ),
        Case(
            "制表符带备注列表头",
            "平台\t账号\t密码\t备注\n淘宝\ttb_t\tTb@111111\t备用",
            listOf(Row("淘宝", "tb_t", "Tb@111111", "备用"))
        ),
        Case(
            "全角逗号紧跟值",
            "账号：a1，密码：b1",
            listOf(Row(null, "a1", "b1"))
        ),
        Case(
            "斜杠隔开三格",
            "淘宝/taobao_01/Tb@123456",
            listOf(Row("淘宝", "taobao_01", "Tb@123456"))
        ),

        // ── 十二、还有哪些抄法会碰上 ─────────────────────────────
        Case(
            "带数字后缀的字段名",
            "账号1：taobao_31\n密码1：Tb@313131",
            listOf(Row(null, "taobao_31", "Tb@313131"))
        ),
        Case(
            "先密码后账号",
            "密码：Tb@333333\n账号：taobao_33",
            listOf(Row(null, "taobao_33", "Tb@333333"))
        ),
        Case(
            "邮箱当账号带标题和备注",
            "淘宝\n邮箱：t34@example.com\n密码：Tb@343434\n备注：备用",
            listOf(Row("淘宝", "t34@example.com", "Tb@343434", "备用"))
        ),
        Case(
            "全角竖线三格",
            "淘宝｜taobao_35｜Tb@353535",
            listOf(Row("淘宝", "taobao_35", "Tb@353535"))
        ),
        Case(
            "斜杠只有两格",
            "taobao_36/Tb@363636",
            listOf(Row(null, "taobao_36", "Tb@363636"))
        ),
        Case(
            "标题账号密码各占一行",
            "淘宝\ntaobao_37\nTb@373737",
            listOf(Row("淘宝", "taobao_37", "Tb@373737"))
        ),
        Case(
            "冒号两段没标题",
            "taobao_38:Tb@383838",
            listOf(Row(null, "taobao_38", "Tb@383838"))
        ),
        Case(
            "方头卡片同一行",
            "【淘宝】账号：taobao_39 密码：Tb@393939",
            listOf(Row("淘宝", "taobao_39", "Tb@393939"))
        ),
        Case(
            "网址单独占开头一行",
            "www.taobao.com\n账号 taobao_40\n密码 Tb@404040",
            listOf(Row(null, "taobao_40", "Tb@404040"))
        ),
        Case(
            "无冒号加备注一行",
            "淘宝\n用户名 taobao_41\n密码 Tb@414141\n备注 老账号",
            listOf(Row("淘宝", "taobao_41", "Tb@414141", "老账号"))
        ),
        Case(
            "英文平台冒号链",
            "steam:demo_steam42:St@424242",
            listOf(Row("steam", "demo_steam42", "St@424242"))
        ),
        Case(
            "空行分开两组键值",
            "账号：u43\n密码：p43\n\n账号：u44\n密码：p44",
            listOf(Row(null, "u43", "p43"), Row(null, "u44", "p44"))
        ),
        Case(
            "等号加和号的环境变量",
            "ACCOUNT=ac46&PASSWORD=pw46",
            listOf(Row(null, "ac46", "pw46"))
        ),
        Case(
            "带端口的登录页",
            "http://192.168.1.1:8080 账号 admin 密码 Ad@474747",
            listOf(Row(null, "admin", "Ad@474747"))
        ),
        Case(
            "中文引号包住的值",
            "账号：“taobao_48” 密码：“Tb@484848”",
            listOf(Row(null, "taobao_48", "Tb@484848"))
        ),
        Case(
            "序号加顿号两条",
            "1、淘宝 taobao_50 Tb@505050\n2、京东 jd_50 Jd@505050",
            listOf(Row("淘宝", "taobao_50", "Tb@505050"), Row("京东", "jd_50", "Jd@505050"))
        ),
        Case(
            "备注里只有一个网址",
            "账号 u51 密码 p51 备注 https://a.example.com",
            listOf(Row(null, "u51", "p51", "https://a.example.com"))
        ),

        // ── 十三、更绕的抄法 ─────────────────────────────────────
        Case(
            "初始密码这种变体字段名",
            "账号：taobao_52\n初始密码：Tb@525252",
            listOf(Row(null, "taobao_52", "Tb@525252"))
        ),
        Case(
            "尾行一句提醒不算记录",
            "登录名：u53\n登录密码：p53\n（注意保密）",
            listOf(Row(null, "u53", "p53"))
        ),
        Case(
            "带验证码字段",
            "淘宝\n账号=taobao_56\n密码=Tb@565656\n验证码=1234",
            listOf(Row("淘宝", "taobao_56", "Tb@565656"))
        ),
        Case(
            "前缀帽加一整行字段",
            "【登录信息】 平台：淘宝 账号：taobao_57 密码：Tb@575757",
            listOf(Row("淘宝", "taobao_57", "Tb@575757"))
        ),
        Case(
            "两行只有账号密码",
            "taobao_58\nTb@585858",
            listOf(Row(null, "taobao_58", "Tb@585858"))
        ),
        Case(
            "标题冒号接账号密码",
            "淘宝：taobao_62 / Tb@626262",
            listOf(Row("淘宝", "taobao_62", "Tb@626262"))
        ),
        Case(
            "竖线夹着带标签的两段",
            "账号 taobao_63 | 密码 Tb@636363",
            listOf(Row(null, "taobao_63", "Tb@636363"))
        ),
        Case(
            "制表符隔开两个字段",
            "账号：taobao_65\t密码：Tb@656565",
            listOf(Row(null, "taobao_65", "Tb@656565"))
        ),
        Case(
            "字段顺序打乱",
            "密码：Tb@616161\n账号：taobao_61\n平台：淘宝",
            listOf(Row("淘宝", "taobao_61", "Tb@616161"))
        ),
        Case(
            "五字平台名加两个词",
            "网易云音乐 wangyi_64 Wy@646464",
            listOf(Row("网易云音乐", "wangyi_64", "Wy@646464"))
        ),
        Case(
            "英文标题三行交替",
            "Gmail\nmail_demo@example.com\nApp@666666",
            listOf(Row("Gmail", "mail_demo@example.com", "App@666666"))
        ),
        Case(
            "链接查询串里带账号名",
            "https://taobao.com/login?user=taobao_60&remember=1",
            listOf(Row(null, "taobao_60"))
        )
    )

    @Test
    fun `样本表逐条核对`() {
        val failures = mutableListOf<String>()
        for (case in cases) {
            val actual = CredentialParser.parseMany(case.text).map {
                Row(title = it.title, user = it.username, pass = it.password, notes = it.notes)
            }
            if (actual.size != case.expect.size) {
                failures += "${case.name}：条数 ${actual.size} ≠ ${case.expect.size}，实际=$actual"
                continue
            }
            case.expect.forEachIndexed { index, expected ->
                if (actual[index] != expected) {
                    failures += "${case.name}：第 ${index + 1} 条 $expected ≠ ${actual[index]}"
                }
            }
        }
        assertEquals("\n" + failures.joinToString("\n"), emptyList<String>(), failures)
    }

    @Test
    fun `样本表至少三十条`() {
        assertTrue("样本表只剩 ${cases.size} 条", cases.size >= 30)
    }
}
