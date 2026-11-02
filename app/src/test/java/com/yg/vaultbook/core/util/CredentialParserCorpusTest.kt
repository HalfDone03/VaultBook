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
        ),

        // ── 十四、换个说法抄同一件事 ─────────────────────────────
        Case(
            "手机号当账号配登录密码",
            "账号：13800001111\n登录密码：Abc@123456",
            listOf(Row(null, "13800001111", "Abc@123456"))
        ),
        Case(
            "登录名配口令",
            "登录名：user_a 口令：Pw@111111",
            listOf(Row(null, "user_a", "Pw@111111"))
        ),
        Case(
            "英文表头两列",
            "Username,Password\nsteam_01,St@111111",
            listOf(Row(null, "steam_01", "St@111111"))
        ),
        Case(
            "英文表头三列带站名",
            "Name,Login,Password\nSteam,steam_02,St@222222",
            listOf(Row("Steam", "steam_02", "St@222222"))
        ),
        Case(
            "同行英文键值之间两个空格",
            "Login: a_1  Password: P@ss111",
            listOf(Row(null, "a_1", "P@ss111"))
        ),
        Case(
            "密码里带空格",
            "账号：a2\n密码：pa ss 123",
            listOf(Row(null, "a2", "pa ss 123"))
        ),
        Case(
            "密码里带逗号",
            "账号：a3\n密码：p,1x",
            listOf(Row(null, "a3", "p,1x"))
        ),
        Case(
            "密码里的和号不是参数",
            "账号：a4\n密码：p&s1",
            listOf(Row(null, "a4", "p&s1"))
        ),
        Case(
            "密码以井号开头",
            "账号：a5\n密码：#tag123",
            listOf(Row(null, "a5", "#tag123"))
        ),
        Case(
            "只有一串数字的密码",
            "123456",
            listOf(Row(null, null, "123456"))
        ),
        Case(
            "只有账号没有密码",
            "账号：only_a",
            listOf(Row(null, "only_a"))
        ),
        Case(
            "空备注不挡识别",
            "淘宝\n账号：a6\n密码：p6\n备注：",
            listOf(Row("淘宝", "a6", "p6"))
        ),
        Case(
            "同一行两遍键值各成一条",
            "账号：a7 密码：p7 账号：a8 密码：p8",
            listOf(Row(null, "a7", "p7"), Row(null, "a8", "p8"))
        ),
        Case(
            "横线分开两组",
            "淘宝\n账号：a9\n密码：p9\n----\n京东\n账号：a10\n密码：p10",
            listOf(Row("淘宝", "a9", "p9"), Row("京东", "a10", "p10"))
        ),
        Case(
            "全角括号包值",
            "平台（京东）账号（jd_1）密码（Jd@111111）",
            listOf(Row("京东", "jd_1", "Jd@111111"))
        ),
        Case(
            "带数字点的键值",
            "1. 账号：a11 密码：p11",
            listOf(Row(null, "a11", "p11"))
        ),
        Case(
            "数字右括号序号带全字段",
            "1) 平台：淘宝 账号：a12 密码：p12",
            listOf(Row("淘宝", "a12", "p12"))
        ),
        Case(
            "中文感叹号收尾",
            "淘宝 账号：a13 密码：p13！",
            listOf(Row("淘宝", "a13", "p13"))
        ),
        Case(
            "链接里带账号密码",
            "https://u_1:p_1@example.com/login",
            listOf(Row(null, "u_1", "p_1"))
        ),
        Case(
            "网址后接账号密码",
            "example.com 账号 a14 密码 p14",
            listOf(Row(null, "a14", "p14"))
        ),
        Case(
            "应用键名打头",
            "app：微信\nuser：wx_1\npass：Wx@111111",
            listOf(Row("微信", "wx_1", "Wx@111111"))
        ),
        Case(
            "JSON 大写键名",
            "{\"UserName\": \"json_1\", \"Password\": \"Js@111111\", \"Site\": \"淘宝\"}",
            listOf(Row("淘宝", "json_1", "Js@111111"))
        ),
        Case(
            "JSON 带备注与网址",
            "{\"site\": \"知乎\", \"user\": \"zh_1\", \"pass\": \"Zh@111111\", \"note\": \"常用\", \"url\": \"https://zhihu.com\"}",
            listOf(Row("知乎", "zh_1", "Zh@111111", "常用"))
        ),
        Case(
            "XML 用 name 与 pwd",
            "<login><name>x_1</name><pwd>X@111111</pwd></login>",
            listOf(Row(null, "x_1", "X@111111"))
        ),
        Case(
            "表格里备注留空也行",
            "平台,账号,密码,备注\n淘宝,t_1,T@111111,\n京东,t_2,Jd@222222,常用",
            listOf(Row("淘宝", "t_1", "T@111111"), Row("京东", "t_2", "Jd@222222", "常用"))
        ),
        Case(
            "制表符三列带表头",
            "平台\t账号\t密码\n淘宝\tt_3\tT@333333",
            listOf(Row("淘宝", "t_3", "T@333333"))
        ),
        Case(
            "英文站冒号链",
            "github:gh_1:Gh@111111",
            listOf(Row("github", "gh_1", "Gh@111111"))
        ),
        Case(
            "中文站冒号链",
            "淘宝:tb_1:Tb@111111",
            listOf(Row("淘宝", "tb_1", "Tb@111111"))
        ),
        Case(
            "空格分开三格",
            "淘宝 tb_2 Tb@222222",
            listOf(Row("淘宝", "tb_2", "Tb@222222"))
        ),
        Case(
            "两行账号密码没有标题",
            "m_1@example.com\nMa@111111",
            listOf(Row(null, "m_1@example.com", "Ma@111111"))
        ),
        Case(
            "斜杠两格",
            "tb_3/Tb@333333",
            listOf(Row(null, "tb_3", "Tb@333333"))
        ),
        Case(
            "斜杠三格带站名",
            "淘宝/tb_4/Tb@444444",
            listOf(Row("淘宝", "tb_4", "Tb@444444"))
        ),
        Case(
            "竖线四格带备注",
            "淘宝|tb_5|Tb@555555|常用",
            listOf(Row("淘宝", "tb_5", "Tb@555555", "常用"))
        ),
        Case(
            "环境变量用和号相连",
            "ACCOUNT=env_1&PASSWORD=Env@111111",
            listOf(Row(null, "env_1", "Env@111111"))
        ),
        Case(
            "方头卡片同一行带字段",
            "【淘宝】账号：a16 密码：p16",
            listOf(Row("淘宝", "a16", "p16"))
        ),
        Case(
            "网址单独一行再给字段",
            "https://x.com\n账号：a17\n密码：p17",
            listOf(Row(null, "a17", "p17"))
        ),
        Case(
            "标题一行后接字段",
            "淘宝\n账号：a18\n密码：p18",
            listOf(Row("淘宝", "a18", "p18"))
        ),
        Case(
            "手机号不单独成条",
            "账号：a19 密码：p19 手机：13800138000",
            listOf(Row(null, "a19", "p19"))
        ),
        Case(
            "等号两侧留空格",
            "账号 = a21\n密码 = p21",
            listOf(Row(null, "a21", "p21"))
        ),
        Case(
            "全角等号不留空格",
            "账号＝a22\n密码＝p22",
            listOf(Row(null, "a22", "p22"))
        ),
        Case(
            "大写键名",
            "USER: cap_1\nPASS: Cap@111111",
            listOf(Row(null, "cap_1", "Cap@111111"))
        ),
        Case(
            "中文密码",
            "账号：a24 密码：中文密码123",
            listOf(Row(null, "a24", "中文密码123"))
        ),
        Case(
            "备注里只有一个网址",
            "账号：a25 密码：p25 备注：https://note.example.com",
            listOf(Row(null, "a25", "p25", "https://note.example.com"))
        ),
        Case(
            "两条之间只有空行",
            "账号：a26 密码：p26\n\n账号：a27 密码：p27",
            listOf(Row(null, "a26", "p26"), Row(null, "a27", "p27"))
        ),
        Case(
            "密码结尾带句号",
            "账号：a28 密码：p28。",
            listOf(Row(null, "a28", "p28"))
        ),
        Case(
            "平台上带引号",
            "\"淘宝\" 账号 a29 密码 p29",
            listOf(Row("淘宝", "a29", "p29"))
        ),
        Case(
            "冒号在账号里",
            "淘宝 admin:Pass@111111",
            listOf(Row("淘宝", "admin", "Pass@111111"))
        ),
        Case(
            "密码里有冒号",
            "账号：a30\n密码：p:30:1",
            listOf(Row(null, "a30", "p:30:1"))
        ),
        Case(
            "带斜线的网址不当三格",
            "https://www.taobao.com/item/123",
            emptyList()
        ),

        // ── 十五、群里转发的与别处的怪写法 ────────────────────────
        Case(
            "聊天记录里夹着账号密码",
            "老王 2024-05-01 10:23\n淘宝账号：tb_wx1\n登录密码：Tb@111111\n收到回我",
            listOf(Row("淘宝", "tb_wx1", "Tb@111111"))
        ),
        Case(
            "繁体字段名",
            "帳號：tw_1\n密碼：Tw@111111",
            listOf(Row(null, "tw_1", "Tw@111111"))
        ),
        Case(
            "密码里有等号",
            "账号 eq_1 密码 a=b@2",
            listOf(Row(null, "eq_1", "a=b@2"))
        ),
        Case(
            "网址带端口",
            "淘宝 taobao_70 Tb@707070 https://taobao.com:8443/login",
            listOf(Row("淘宝", "taobao_70", "Tb@707070"))
        ),
        Case(
            "IP 当网址",
            "路由器 admin Rt@121212 192.168.1.1",
            listOf(Row("路由器", "admin", "Rt@121212"))
        ),
        Case(
            "同行两个英文键值",
            "user: eng_1 pass: En@111111",
            listOf(Row(null, "eng_1", "En@111111"))
        ),
        Case(
            "顿号当分隔",
            "淘宝、tb_71、Tb@717171",
            listOf(Row("淘宝", "tb_71", "Tb@717171"))
        ),
        Case(
            "逗号分隔三列带引号",
            "\"淘宝\",\"tb_72\",\"Tb@727272\"",
            listOf(Row("淘宝", "tb_72", "Tb@727272"))
        ),
        Case(
            "制表符四列",
            "淘宝\ttb_73\tTb@737373\t常用",
            listOf(Row("淘宝", "tb_73", "Tb@737373", "常用"))
        ),
        Case(
            "备注里带逗号",
            "账号 c_1 密码 C@111111 备注 常用,备用",
            listOf(Row(null, "c_1", "C@111111", "常用,备用"))
        ),
        Case(
            "三行只有值没有标签",
            "淘宝\ntb_74\nTb@747474",
            listOf(Row("淘宝", "tb_74", "Tb@747474"))
        ),
        Case(
            "确认密码重复出现",
            "账号 rp_1 密码 Rp@111111 确认密码 Rp@111111",
            listOf(Row(null, "rp_1", "Rp@111111"))
        ),
        Case(
            "键值对之间用逗号",
            "账号:cm_1，密码:Cm@111111",
            listOf(Row(null, "cm_1", "Cm@111111"))
        ),
        Case(
            "中文键用和号相连",
            "账号=amp_1&密码=Amp@111111",
            listOf(Row(null, "amp_1", "Amp@111111"))
        ),
        Case(
            "标题带括号",
            "淘宝（中国）账号 cn_1 密码 Cn@111111",
            listOf(Row("淘宝（中国）", "cn_1", "Cn@111111"))
        ),
        Case(
            "方头括号里带冒号",
            "【淘宝：购物】\n账号 fk_1\n密码 Fk@111111",
            listOf(Row("淘宝", "fk_1", "Fk@111111"))
        ),
        Case(
            "网址单独一行带路径",
            "淘宝\nhttps://taobao.com/item/9\n账号 pth_1\n密码 Pth@111111",
            listOf(Row("淘宝", "pth_1", "Pth@111111"))
        ),
        Case(
            "纯数字六位密码",
            "淘宝 密码 654321",
            listOf(Row("淘宝", null, "654321"))
        ),
        Case(
            "手机号加登录密码",
            "13800138000\nLg@111111",
            listOf(Row(null, "13800138000", "Lg@111111"))
        ),
        Case(
            "大写英文键名",
            "ACCOUNT: up_1\nPASSWORD: Up@111111",
            listOf(Row(null, "up_1", "Up@111111"))
        ),
        Case(
            "Pwd 缩写键名",
            "Login: pwd_1\nPwd: Pwd@111111",
            listOf(Row(null, "pwd_1", "Pwd@111111"))
        ),
        Case(
            "站点名与账号粘连",
            "知乎zhihu_75 Zh@757575",
            listOf(Row("知乎", "zhihu_75", "Zh@757575"))
        ),
        Case(
            "空行分开的聊天两段",
            "淘宝\n账号 sp_1\n密码 Sp@111111\n\n京东\n账号 sp_2\n密码 Sp@222222",
            listOf(
                Row("淘宝", "sp_1", "Sp@111111"),
                Row("京东", "sp_2", "Sp@222222")
            )
        ),
        Case(
            "英文站冒号链两条",
            "steam:st_76:St@767676\ngithub:gh_77:Gh@777777",
            listOf(
                Row("steam", "st_76", "St@767676"),
                Row("github", "gh_77", "Gh@777777")
            )
        ),
        Case(
            "等号两侧留空格",
            "平台 = 淘宝\n账号 = eqs_1\n密码 = Eqs@111111",
            listOf(Row("淘宝", "eqs_1", "Eqs@111111"))
        ),
        Case(
            "密码结尾带空格",
            "账号 spc_1 密码 Spc@111111  ",
            listOf(Row(null, "spc_1", "Spc@111111"))
        ),
        Case(
            "同一行先标题后两组键",
            "淘宝 账号: tt_1 密码: Tt@111111",
            listOf(Row("淘宝", "tt_1", "Tt@111111"))
        ),
        Case(
            "用户名口令中文全角",
            "用户名：ｕｓｅｒ１\n口令：Ｆｗ＠１１１１１１",
            listOf(Row(null, "ｕｓｅｒ１", "Ｆｗ＠１１１１１１"))
        ),
        Case(
            "JSON 里密码是数字",
            "{\"site\":\"淘宝\",\"user\":\"jnum\",\"pass\":998877}",
            listOf(Row("淘宝", "jnum", "998877"))
        ),
        Case(
            "XML 属性带命名空间前缀",
            "<row site=\"淘宝\" acct=\"ns_1\" pwd=\"Ns@111111\"/>",
            listOf(Row("淘宝", "ns_1", "Ns@111111"))
        ),
        Case(
            "YAML 单条多行",
            "site: 淘宝\nuser: yml_1\npass: Yml@111111",
            listOf(Row("淘宝", "yml_1", "Yml@111111"))
        ),
        Case(
            "全角竖线分隔两条",
            "淘宝｜fj_1｜Fj@111111\n京东｜fj_2｜Fj@222222",
            listOf(
                Row("淘宝", "fj_1", "Fj@111111"),
                Row("京东", "fj_2", "Fj@222222")
            )
        ),
        Case(
            "密码里有井号与美元",
            "账号 sym_1 密码 Sy#m$1@111",
            listOf(Row(null, "sym_1", "Sy#m$1@111"))
        ),
        Case(
            "账号是邮箱密码是口令",
            "邮箱：mb_78@example.com 口令：Mb@111111",
            listOf(Row(null, "mb_78@example.com", "Mb@111111"))
        ),
        Case(
            "前后各有一句废话",
            "帮你记一下\n账号：ok_1\n密码：Ok@111111\n别外传",
            listOf(Row("帮你记一下", "ok_1", "Ok@111111"))
        ),
        Case(
            "网页复制的表头带空格",
            "平台 , 账号 , 密码\n淘宝 , hd_1 , Hd@111111",
            listOf(Row("淘宝", "hd_1", "Hd@111111"))
        ),
        Case(
            "分隔线夹两段",
            "淘宝\ntb_79\nTb@797979\n-----------\n京东\njd_80\nJd@808080",
            listOf(
                Row("淘宝", "tb_79", "Tb@797979"),
                Row("京东", "jd_80", "Jd@808080")
            )
        ),
        Case(
            "只有账号没有密码",
            "账号 so_1",
            listOf(Row(null, "so_1", null))
        ),
        Case(
            "备注单独一段长文",
            "账号 nt_1\n密码 Nt@111111\n备注 这个账号是给爸妈用的，别乱改",
            listOf(Row(null, "nt_1", "Nt@111111", "这个账号是给爸妈用的，别乱改"))
        ),
        Case(
            "序号带点在键值前",
            "1. 账号: idx_1\n2. 密码: Idx@111111",
            listOf(Row(null, "idx_1", "Idx@111111"))
        ),
        Case(
            "标题带表情",
            "淘宝🛒\n账号 emo_1\n密码 Emo@111111",
            listOf(Row("淘宝", "emo_1", "Emo@111111"))
        ),

        // ── 十六、开发者后台与笔记软件里捞出来的 ─────────────────
        Case(
            "后台密钥成对出现",
            "AppID：wx0000000001\nAppSecret：Sec@0000",
            listOf(Row(null, "wx0000000001", "Sec@0000"))
        ),
        Case(
            "CSV 表头带空格",
            " 平台 , 账号 , 密码 \n淘宝 , csv_1 , Csv@111111",
            listOf(Row("淘宝", "csv_1", "Csv@111111"))
        ),
        Case(
            "密码里有中文也有符号",
            "账号：cn_1\n密码：密码123！@#",
            listOf(Row(null, "cn_1", "密码123！@#"))
        ),
        Case(
            "账号本身是中文",
            "平台：单位内网\n账号：张三\n密码：Zs@20240101",
            listOf(Row("单位内网", "张三", "Zs@20240101"))
        ),
        Case(
            "前面一句引言后面两组",
            "登录信息如下：\n淘宝：mk_1 / Mk@111111\n京东：mk_2 / Mk@222222",
            listOf(Row("淘宝", "mk_1", "Mk@111111"), Row("京东", "mk_2", "Mk@222222"))
        ),
        Case(
            "表格密码列在前",
            "密码,账号\nPw@111111,rev_1\nPw@222222,rev_2",
            listOf(Row(null, "rev_1", "Pw@111111"), Row(null, "rev_2", "Pw@222222"))
        ),
        Case(
            "全角冒号配全角空格",
            "账号：　fw_1\n密码：　Fw@111111",
            listOf(Row(null, "fw_1", "Fw@111111"))
        ),
        Case(
            "JSON 只有账号密码",
            "[{\"账号\":\"json_cn_1\",\"密码\":\"Jc@111111\"},{\"账号\":\"json_cn_2\",\"密码\":\"Jc@222222\"}]",
            listOf(Row(null, "json_cn_1", "Jc@111111"), Row(null, "json_cn_2", "Jc@222222"))
        ),
        Case(
            "JSON 中文键带平台",
            "[{\"平台\":\"淘宝\",\"账号\":\"jt_1\",\"密码\":\"Jt@111111\"}]",
            listOf(Row("淘宝", "jt_1", "Jt@111111"))
        ),
        Case(
            "XML 里套 CDATA",
            "<list><item><site>知乎</site><user>cdata_1</user><pass><![CDATA[Ca@111111]]></pass></item></list>",
            listOf(Row("知乎", "cdata_1", "Ca@111111"))
        ),
        Case(
            "YAML 两条用横线分",
            "- site: 淘宝\n  user: y_1\n  pass: Y@111111\n- site: 京东\n  user: y_2\n  pass: Y@222222",
            listOf(Row("淘宝", "y_1", "Y@111111"), Row("京东", "y_2", "Y@222222"))
        ),
        Case(
            "一行三组键值带备注",
            "用户名：all_1 密码：All@111111 备注：备用",
            listOf(Row(null, "all_1", "All@111111", "备用"))
        ),
        Case(
            "网址带锚点",
            "http://localhost:8080/admin#login 账号：anc_1 密码：Anc@111111",
            listOf(Row(null, "anc_1", "Anc@111111"))
        ),
        Case(
            "账号后面带身份说明",
            "平台：后台\n账号：adm_1（管理员）\n密码：Adm@111111",
            listOf(Row("后台", "adm_1（管理员）", "Adm@111111"))
        ),
        Case(
            "备注里既有冒号又有等号",
            "账号：note_1\n密码：Note@111111\n备注：绑定邮箱:x@y.com，验证码=手机",
            listOf(Row(null, "note_1", "Note@111111", "绑定邮箱:x@y.com，验证码=手机"))
        ),
        Case(
            "破折号分隔三格",
            "网易云音乐 - dash_1 - Dash@111111",
            listOf(Row("网易云音乐", "dash_1", "Dash@111111"))
        ),
        Case(
            "多行逗号三格没表头",
            "淘宝,tc_1,Tc@111111\n京东,tc_2,Tc@222222\n知乎,tc_3,Tc@333333",
            listOf(
                Row("淘宝", "tc_1", "Tc@111111"),
                Row("京东", "tc_2", "Tc@222222"),
                Row("知乎", "tc_3", "Tc@333333")
            )
        ),
        Case(
            "制表符三列没表头",
            "淘宝\ttt_1\tTt@111111\n京东\ttt_2\tTt@222222",
            listOf(Row("淘宝", "tt_1", "Tt@111111"), Row("京东", "tt_2", "Tt@222222"))
        ),
        Case(
            "井号开头的注释不算记录",
            "# 这是上次记的\n账号：cm_1\n密码：Cm@111111",
            listOf(Row(null, "cm_1", "Cm@111111"))
        ),
        Case(
            "Markdown 反引号包着值",
            "账号：`md_1`\n密码：`Md@111111`",
            listOf(Row(null, "md_1", "Md@111111"))
        ),
        Case(
            "Markdown 加粗包着密码",
            "账号：bd_1\n密码：**Bd@111111**",
            listOf(Row(null, "bd_1", "Bd@111111"))
        ),
        Case(
            "Markdown 表格带分隔行",
            "| 平台 | 账号 | 密码 |\n| --- | --- | --- |\n| 淘宝 | mt_1 | Mt@111111 |",
            listOf(Row("淘宝", "mt_1", "Mt@111111"))
        ),
        Case(
            "HTML 表格里的两行",
            "<tr><td>淘宝</td><td>ht_1</td><td>Ht@111111</td></tr><tr><td>京东</td><td>ht_2</td><td>Ht@222222</td></tr>",
            listOf(Row("淘宝", "ht_1", "Ht@111111"), Row("京东", "ht_2", "Ht@222222"))
        ),
        Case(
            "书名号包着平台名",
            "《淘宝》账号 bm_1 密码 Bm@111111",
            listOf(Row("淘宝", "bm_1", "Bm@111111"))
        ),
        Case(
            "全角括号包着备注",
            "账号：fw_2\n密码：Fw@222222\n备注：（内部使用）",
            listOf(Row(null, "fw_2", "Fw@222222", "（内部使用）"))
        ),
        Case(
            "同一账号出现两遍",
            "账号：dup_1\n账号：dup_1\n密码：Dup@111111",
            listOf(Row(null, "dup_1", "Dup@111111"))
        ),
        Case(
            "手机号配六位数字密码",
            "账号：13800000123\n密码：135790",
            listOf(Row(null, "13800000123", "135790"))
        ),
        Case(
            "密码是纯符号",
            "账号：sym_1\n密码：!@#$%^&*",
            listOf(Row(null, "sym_1", "!@#$%^&*"))
        ),
        Case(
            "只有标题和密码",
            "路由器后台\n密码：Rt@2024",
            listOf(Row("路由器后台", null, "Rt@2024"))
        ),
        Case(
            "网址开头后面接键值",
            "https://mail.example.com 账号：url_1 密码：Url@111111",
            listOf(Row(null, "url_1", "Url@111111"))
        ),
        Case(
            "两段只有前一段有标题",
            "淘宝\n账号：seg_1\n密码：Seg@111111\n\n账号：seg_2\n密码：Seg@222222",
            listOf(Row("淘宝", "seg_1", "Seg@111111"), Row(null, "seg_2", "Seg@222222"))
        ),
        Case(
            "英文键名但值是中文",
            "Username: 老王\nPassword: Lw@111111",
            listOf(Row(null, "老王", "Lw@111111"))
        ),
        Case(
            "冒号写在账号里面",
            "平台：GitLab\n账号：git@corp.example.com:dev\n密码：Git@111111",
            listOf(Row("GitLab", "git@corp.example.com:dev", "Git@111111"))
        ),
        Case(
            "密码里有等号与加号",
            "账号：b64_1\n密码：aB+/cD==",
            listOf(Row(null, "b64_1", "aB+/cD=="))
        ),

        // ── 第十七组：换设备、换软件时又碰上的写法 ──────────────────────
        Case(
            "微信里两行抄一条",
            "淘宝账号：wxcopy_1\n淘宝密码：Wx@111111",
            listOf(Row("淘宝", "wxcopy_1", "Wx@111111"))
        ),
        Case(
            "同一平台两条连写",
            "微博账号 wb_1 密码 Wb@111111\n微博账号 wb_2 密码 Wb@222222",
            listOf(Row("微博", "wb_1", "Wb@111111"), Row("微博", "wb_2", "Wb@222222"))
        ),
        Case(
            "ini 段落写法",
            "[taobao]\nuser=ini_1\npassword=Ini@111111",
            listOf(Row(null, "ini_1", "Ini@111111"))
        ),
        Case(
            "五列表格多一列无关",
            "平台,账号,密码,备注,更新时间\n淘宝,t5_1,T5@111111,常用,2026-01-01",
            listOf(Row("淘宝", "t5_1", "T5@111111", "常用"))
        ),
        Case(
            "登录 ID 这种带空格的键名",
            "Login ID: lid_1\nPass Word: Lid@111111",
            listOf(Row(null, "lid_1", "Lid@111111"))
        ),
        Case(
            "密码是中文",
            "平台：内网\n账号：cn_1\n密码：内网密码",
            listOf(Row("内网", "cn_1", "内网密码"))
        ),
        Case(
            "账号带前导零",
            "平台：门禁\n账号：0012345\n密码：Gate@111",
            listOf(Row("门禁", "0012345", "Gate@111"))
        ),
        Case(
            "结尾注明已修改",
            "账号：chg_1\n密码：Chg@111111\n（已修改）",
            listOf(Row(null, "chg_1", "Chg@111111"))
        ),
        Case(
            "序号加顿号",
            "1、平台：淘宝\n2、账号：nu_1\n3、密码：Nu@111111",
            listOf(Row("淘宝", "nu_1", "Nu@111111"))
        ),
        Case(
            "哈希与美元符号的密码",
            "账号：hd_1\n密码：Hd#1$2@3",
            listOf(Row(null, "hd_1", "Hd#1$2@3"))
        ),
        Case(
            "英文缩写平台名",
            "AWS\n账号：aws_1\n密码：Aws@111111",
            listOf(Row("AWS", "aws_1", "Aws@111111"))
        ),
        Case(
            "冒号链带中文账号",
            "淘宝:中文账号_1:Tb@111111",
            listOf(Row("淘宝", "中文账号_1", "Tb@111111"))
        ),
        Case(
            "两段之间用虚线隔开",
            "淘宝\n账号：dash_1\n密码：Dash@111111\n----\n京东\n账号：dash_2\n密码：Dash@222222",
            listOf(Row("淘宝", "dash_1", "Dash@111111"), Row("京东", "dash_2", "Dash@222222"))
        ),
        Case(
            "表格里标题列写成应用名",
            "应用名,登录名,登录密码\n淘宝,ap_1,Ap@111111",
            listOf(Row("淘宝", "ap_1", "Ap@111111"))
        ),
        Case(
            "XML 一条带备注",
            "<item><site>京东</site><user>x1</user><pass>X@111111</pass><note>单位</note></item>",
            listOf(Row("京东", "x1", "X@111111", "单位"))
        ),
        Case(
            "JSON 一条带网址",
            "{\"site\":\"知乎\",\"user\":\"j1\",\"pass\":\"J@111111\",\"url\":\"https://www.zhihu.com\"}",
            listOf(Row("知乎", "j1", "J@111111"))
        ),
        Case(
            "密码前有冒号空白",
            "账号：sp_1\n密码： Sp@111111",
            listOf(Row(null, "sp_1", "Sp@111111"))
        ),
        Case(
            "平台名带后缀平台两字",
            "淘宝平台\n账号：pf_1\n密码：Pf@111111",
            listOf(Row("淘宝平台", "pf_1", "Pf@111111"))
        ),
        Case(
            "只有一行三个值没有键名",
            "淘宝 tb_2 Tb@222222",
            listOf(Row("淘宝", "tb_2", "Tb@222222"))
        ),
        Case(
            "地址里带端口与路径",
            "账号：pt_1\n密码：Pt@111111\n网址：http://192.168.1.1:8080/login",
            listOf(Row(null, "pt_1", "Pt@111111"))
        ),
        Case(
            "方头括号里带编号",
            "【1】淘宝\n账号：nb_1\n密码：Nb@111111",
            listOf(Row("淘宝", "nb_1", "Nb@111111"))
        ),
        Case(
            "冒号后带说明再给值",
            "账号（登录用）：ex_1\n密码（区分大小写）：Ex@111111",
            listOf(Row(null, "ex_1", "Ex@111111"))
        ),
        Case(
            "密码是日期",
            "平台：路由器\n账号：admin\n密码：20260101",
            listOf(Row("路由器", "admin", "20260101"))
        ),
        Case(
            "两行只有值交替带标题",
            "网易云音乐\nmusic_1\nMu@111111",
            listOf(Row("网易云音乐", "music_1", "Mu@111111"))
        ),
        Case(
            "制表符分隔带表头两列",
            "账号\t密码\n\t",
            emptyList()
        ),
        // ── 第十八组：连接串、命令行、聊天记录这些边角 ──────────────────
        Case(
            "数据库连接串",
            "postgres://demo_user:Db@111111@db.example.com:5432/app",
            listOf(Row(null, "demo_user", "Db@111111"))
        ),
        Case(
            "连接串里没有账号",
            "redis://:Red@111111@cache.example.com:6379",
            listOf(Row(null, null, "Red@111111"))
        ),
        Case(
            "命令行里带账号密码",
            "mysql -u db_1 -p Db@222222",
            listOf(Row("mysql", "db_1", "Db@222222"))
        ),
        Case(
            "竖线两边都带键名",
            "淘宝 | 账号：vi_1 | 密码：Vi@111111",
            listOf(Row("淘宝", "vi_1", "Vi@111111"))
        ),
        Case(
            "中文分号隔开三个字段",
            "用户名：sc_1；密码：Sc@111111；邮箱：sc_1@example.com",
            listOf(Row(null, "sc_1", "Sc@111111"))
        ),
        Case(
            "和号连两个字段",
            "账号=amp_1&密码=Amp@111111",
            listOf(Row(null, "amp_1", "Amp@111111"))
        ),
        Case(
            "聊天记录里带发信人",
            "小李: 淘宝账号 tb_9 密码 Tb@999999",
            listOf(Row("淘宝", "tb_9", "Tb@999999"))
        ),
        Case(
            "先密码后账号",
            "密码 Tb@888888 账号 tb_8",
            listOf(Row(null, "tb_8", "Tb@888888"))
        ),
        Case(
            "后台三件套多一个无关字段",
            "AppID：wx0001\nAppSecret：As@111111\n商户号：1900000109",
            listOf(Row(null, "wx0001", "As@111111"))
        ),
        Case(
            "登录名与登录地址",
            "登录名：lg_1\n登录密码：Lg@111111\n登录地址：https://a.example.com",
            listOf(Row(null, "lg_1", "Lg@111111"))
        ),
        Case(
            "只有密码一段",
            "密码：123456",
            listOf(Row(null, null, "123456"))
        ),
        Case(
            "只有账号一段",
            "账号：only_1",
            listOf(Row(null, "only_1", null))
        ),
        Case(
            "密码里带竖线",
            "账号：pipe_1\n密码：Pi|pe@111111",
            listOf(Row(null, "pipe_1", "Pi|pe@111111"))
        ),
        Case(
            "密码结尾带分号",
            "账号：semi_1\n密码：Se@111111;",
            listOf(Row(null, "semi_1", "Se@111111"))
        ),
        Case(
            "中文姓名当账号",
            "用户：张三\n密码：Zs@111111",
            listOf(Row(null, "张三", "Zs@111111"))
        ),
        Case(
            "英文简写键名两行",
            "email: z@example.com\npass: Z@111111",
            listOf(Row(null, "z@example.com", "Z@111111"))
        ),
        Case(
            "带一次性密码的字段",
            "账号：zh_1\n密码：Zh@111111\nTOTP：JBSWY3DPEHPK3PXP",
            listOf(Row(null, "zh_1", "Zh@111111"))
        ),
        Case(
            "引号包住整段键值",
            "\"账号：q_1 密码：Q@111111\"",
            listOf(Row(null, "q_1", "Q@111111"))
        ),
        Case(
            "两列制表符带引号",
            "\"账号\"\t\"密码\"\n\"tq_1\"\t\"Tq@111111\"",
            listOf(Row(null, "tq_1", "Tq@111111"))
        ),
        Case(
            "网址在前键值在后",
            "https://mail.example.com\n账号：m_1\n密码：M@111111",
            listOf(Row(null, "m_1", "M@111111"))
        ),
        // 第十九组：手上真会碰到、但前面几组没覆盖到的写法。
        Case(
            "前面顶着时间戳",
            "2026-11-02 22:01 淘宝 账号 ts_1 密码 Ts@111111",
            listOf(Row("淘宝", "ts_1", "Ts@111111"))
        ),
        Case(
            "聊天记录带发信人",
            "小明：淘宝 账号 ch_1 密码 Ch@111111",
            listOf(Row("淘宝", "ch_1", "Ch@111111"))
        ),
        Case(
            "键与值之间是全角空格",
            "账号　zx_1\n密码　Zx@111111",
            listOf(Row(null, "zx_1", "Zx@111111"))
        ),
        Case(
            "密码是一串符号",
            "账号：sym_1\n密码：!@#$%^&*",
            listOf(Row(null, "sym_1", "!@#$%^&*"))
        ),
        Case(
            "密码是 base64",
            "账号：b64_1\n密码：YkA5OXh5ejE=",
            listOf(Row(null, "b64_1", "YkA5OXh5ejE="))
        ),
        Case(
            "会员号当账号",
            "会员号：vip_1\n密码：Vip@111111",
            listOf(Row(null, "vip_1", "Vip@111111"))
        ),
        Case(
            "一行四个键带网址",
            "账号：f4_1 密码：F4@111111 备注：测试 网址：f4.example.com",
            listOf(Row(null, "f4_1", "F4@111111", "测试"))
        ),
        Case(
            "英文表头写全称",
            "Name,Login,Password\n淘宝,n_1,N@111111",
            listOf(Row("淘宝", "n_1", "N@111111"))
        ),
        Case(
            "斜杠隔开两个键",
            "账号：s1_1 / 密码：S1@111111",
            listOf(Row(null, "s1_1", "S1@111111"))
        ),
        Case(
            "密码里有分号",
            "账号：semi_2\n密码：Se;mi@111111",
            listOf(Row(null, "semi_2", "Se;mi@111111"))
        ),
        Case(
            "账号里有加号",
            "账号：a+b_1\n密码：Ab@111111",
            listOf(Row(null, "a+b_1", "Ab@111111"))
        ),
        Case(
            "只有标题和账号",
            "淘宝\n账号：only_2",
            listOf(Row("淘宝", "only_2", null))
        ),
        Case(
            "中文括号包着整条",
            "（淘宝 账号：br_1 密码：Br@111111）",
            listOf(Row("淘宝", "br_1", "Br@111111"))
        ),
        Case(
            "Excel 复制带引号制表符",
            "\"账号\"\t\"密码\"\r\n\"ex_1\"\t\"Ex@111111\"",
            listOf(Row(null, "ex_1", "Ex@111111"))
        ),
        Case(
            "yaml 值带引号",
            "account: \"y_1\"\npassword: \"Y@111111\"",
            listOf(Row(null, "y_1", "Y@111111"))
        ),
        Case(
            "json 最外层是数组",
            "[{\"user\":\"j1\",\"pass\":\"J1@111111\"},{\"user\":\"j2\",\"pass\":\"J2@111111\"}]",
            listOf(Row(null, "j1", "J1@111111"), Row(null, "j2", "J2@111111"))
        ),
        Case(
            "密码前后带空格",
            "账号：sp_1\n密码：  Sp@111111  ",
            listOf(Row(null, "sp_1", "Sp@111111"))
        ),
        Case(
            "先密码后账号",
            "密码：rev_p 账号：rev_u",
            listOf(Row(null, "rev_u", "rev_p"))
        ),
        Case(
            "书名号包着平台名",
            "《淘宝》账号 bk_1 密码 Bk@111111",
            listOf(Row("淘宝", "bk_1", "Bk@111111"))
        ),
        Case(
            "备注是一句带逗号的话",
            "账号：lg_2 密码：Lg@111111 备注：备用账号，平时不用",
            listOf(Row(null, "lg_2", "Lg@111111", "备用账号，平时不用"))
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
