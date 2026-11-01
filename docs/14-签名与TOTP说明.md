# docs/14 · 发布签名与 TOTP 实现说明

> 本阶段对应版本 **1.2.0**（versionCode 3）。前序：`docs/13-W6实现说明.md`。
> 代码规模：`main` 68 个 .kt / 12180 行，`test` 21 个 .kt / 3273 行，**323 项单元测试全绿**，lint 0 error 0 warning。

---

## 1. 交付清单

### 1.1 发布签名（不代持私钥）

| 文件 | 改动 |
|---|---|
| `app/build.gradle.kts` | 顶部 `import java.util.Properties`；读 `keystore.properties`；`signingConfigs { create("release") }` 写在 `buildTypes` 之前；`enableV1Signing = false`、V2/V3/V4 = true |
| `keystore.properties.example` | 新建。口令与 .jks 路径的模板 + keytool 命令 + 三条警告 |
| `.gitignore` | 追加 `/keystore`、`/.toolchain`、`/dist`、`/app/schemas/*.json.bak` |

**为什么没有 .jks 文件**：生成密钥属于"创建长期私密凭据"的操作，被安全策略拦下（`secret.ext.jks` 规则）。这不是技术障碍，是刻意的边界——**签名私钥应该由你自己生成并保管**，我把它写进你的工程反而是风险：万一这份对话记录、这个工作目录被人看到，等于把发版权交出去了。

### 1.2 TOTP（两步验证码）

| 文件 | 职责 |
|---|---|
| `core/util/Totp.kt` | 纯 JVM：Base32 解码、otpauth:// 解析、RFC 6238 计算 |
| `ui/TotpCard.kt` | 详情页的动态码卡片（每秒刷新 + 进度条 + 密钥折叠） |
| `ui/EntryDetailScreen.kt` | 在密码卡片后插入 `TotpCard`；移除备注卡片里原来的"动态码密钥"文本行 |
| `ui/EntryEditScreen.kt` | 新增「动态码密钥（可选）」输入框 + 即时校验反馈 |
| `app/src/test/java/.../TotpTest.kt` | 34 项，含 RFC 6238 附录 B 全部向量 |

---

## 2. 签名：三个必须按顺序做的事

### 2.1 生成密钥

```bash
keytool -genkeypair -v \
  -keystore keystore/vaultbook-release.jks \
  -alias vaultbook \
  -keyalg RSA -keysize 4096 -validity 10000 \
  -storetype PKCS12
```

`-validity 10000` 约 27 年。Google Play 要求密钥有效期至少到 2033 年之后，这个数字够用。

### 2.2 填 `keystore.properties`

复制 `keystore.properties.example` 为 `keystore.properties`（该文件已在 `.gitignore` 里）：

```properties
storeFile=keystore/vaultbook-release.jks
storePassword=你的口令
keyAlias=vaultbook
keyPassword=你的口令
```

**PKCS12 格式下 `storePassword` 与 `keyPassword` 必须相同**。这是 PKCS12 的规范限制，不是本项目的要求；填不同的值 Gradle 会报 `Keystore was tampered with, or password was incorrect`。

### 2.3 构建

```powershell
gradle :app:assembleRelease
```

有 `keystore.properties` 且配置完整 → 产出**已签名**的 `app-release.apk`；
文件缺失或不完整 → 产出 `app-release-unsigned.apk`，并打印一条警告：

```
[VaultBook] keystore.properties 存在，但 storeFile/口令不完整或 .jks 找不到 → release 将产出未签名 APK。
```

**为什么不静默退回未签名**：如果你以为签名生效、把未签名包传上去，或者装到手机上发现"应用未安装"，排查方向会完全错。

### 2.4 为什么关掉 v1 签名

`minSdk = 26`（Android 8.0）。v1（JAR 签名）只对 Android 7.0 以下的安装校验有意义，从 24 起系统优先看 v2/v3。留着它只是让 APK 变大、让签名流程多一步失败可能。

---

## 3. TOTP：三个设计决定

### 3.1 每秒重算，而不是"等边界刷新"

很多实现只在下个 30 秒边界刷新一次。问题是定时器会漂移：设备休眠、系统时间被改、应用被冻结后恢复——任何一次边界算错，之后**永久错位**，用户看到的验证码永远是错的，而且看不出来是哪里错。

代价是每秒一次 HMAC-SHA1。实测可以忽略（一次 HMAC-SHA1 是微秒级）。

### 3.2 剩余 ≤5 秒时整个卡片转红

用户输验证码时最怕的是"输到一半码变了"。数字本身变化不明显，颜色变化一眼能看到。

### 3.3 密钥默认折叠

显示验证码是必要的，亮出密钥不是。默认显示 `JBSWY3DPEHP···`，点一下才展开。展示时若输入的是整段 `otpauth://` URI，只显示 Base32 本体（把 issuer/account 一并显示出来没必要）。

---

## 4. TOTP 实现里踩的坑

### 4.1 Base32 不能用 URLDecoder

`otpauth://` URI 的 label 与参数值可能含 URL 编码。`java.net.URLDecoder` 会把 `+` 解成空格——但 Base32 字符集**不含 `+`**，而某些网站的 issuer 里 `+` 是字面量。所以自带了一个只处理 `%XX` 的极简解码。

### 4.2 RFC 6238 测试向量的 SHA512 密钥是 64 字节

附录 B 的密钥是 ASCII `"12345678901234567890"`（20 字节），SHA1 用 20 字节、SHA256 用 32 字节、**SHA512 用 64 字节**——后两者是"把这段字符串重复到目标长度"。

最初我按"32 字节拼两遍"写 SHA512，结果 **SHA1/SHA256 十条全绿、只有 SHA512 两条失败**：

```
expected:<90693936> but was:<00165310>
```

这种"只错一小部分"的失败最容易被误判成"算法实现有边界问题"，实际是**测试数据本身错**。记一笔：向量测试跑挂了，先怀疑向量。

### 4.3 `hotp://` 必须明确拒绝

TOTP 是时间驱动的，HOTP 是计数器驱动的（每次生成都递增）。把 HOTP 的密钥当 TOTP 用，生成的码永远是错的。所以 `parseOtpAuthUri` 里 `type != "totp"` 直接返回 null。

### 4.4 解析失败也要把中间密钥清零

`parseConfig` 内部会 `parseBase32` 出一个 `ByteArray`。若后续校验失败（位数越界、周期为 0），这个数组必须 `fill(0)` 再返回 null——否则每次输入框里敲错一次，内存里就多留一份明文密钥。

---

## 5. 本阶段修掉的问题

| # | 问题 | 后果 | 修法 |
|---|---|---|---|
| 1 | **PowerShell 批量替换破坏了 `EntryEditScreen.kt` 的中文编码** | 文件里 89 处乱码、编译报 `Expecting '"'`，且工程没有 git 历史可回滚 | 用 GBK→UTF-8 逆向恢复 + 逐行按语义补回；见 §6 |
| 2 | `signingConfigs` 写在 `buildTypes` 之后 | `UnknownDomainObjectException`，release 构建直接失败 | 移到前面 |
| 3 | Gradle 脚本里 `java.util.Properties()` Unresolved | 编译脚本失败 | 顶部显式 `import java.util.Properties`（`java` 被 `JavaPluginExtension` 遮蔽） |
| 4 | SHA512 测试向量密钥长度写错 | 两条测试失败，误以为算法有 bug | 按 RFC 用 64 字节 |

---

## 6. 那次编码事故的完整复盘

**怎么发生的**：我用 PowerShell 做一次批量改名：

```powershell
(Get-Content $f -Raw) -replace 'resolveTotpConfig','Totp.parseConfig' |
  Set-Content $f -Encoding utf8 -NoNewline
```

`Get-Content -Raw` 在没有 `-Encoding` 时按系统默认代码页（这台机器是 GBK）解码 UTF-8 文件，于是每个中文字符变成两三个乱码字符；`Set-Content -Encoding utf8` 再把这些乱码按 UTF-8 写回。原始字节信息在**代码页无法映射的位置会变成 `?`（0x3F）**，这部分是不可逆的。

**为什么难修**：`.git` 不存在（工程从未初始化 git），没有源码备份，`app/build` 里只有 `.class`，环境里没有 JVM 反编译器。

**怎么恢复的**：

1. 读当前文件（UTF-8）→ 得到 25862 字符
2. 用 GBK 反编码回字节 → 27588 字节（比原文多，因为乱码字符多是两字节 GBK）
3. 严格 UTF-8 解码 → 25285 字符，**残留 89 个 U+FFFD**
4. 用脚本按行号定位 41 个受影响行，逐行按语义补回标点与被吞掉的换行
5. 修完的 3 处结构问题：`FilterChip(` 那行丢了开括号、`val totpPeriod = remember(totp) {` 整行丢失、`/**` 重复了一行
6. 编译 → 通过

**教训（已写进工程规范）**：

- **改源码只用编辑器级工具（`edit`/`write`），不用 shell 文本替换**。shell 重定向会带上代码页与换行符的隐式转换，而源码是 UTF-8。
- 工程该有 git。这次能救回来是因为乱码恰好可逆；如果替换发生在 BMP 之外的字符（emoji）上，就彻底丢了。
- 备份意识：批量改动前先复制一份 `.bak`。

---

## 7. 真机验证清单

### 7.1 TOTP（5 步）

1. 任意支持两步验证的网站开启 2FA，选「无法扫描二维码」，复制那串 `otpauth://totp/...`
2. 账号本 → 新建条目 → 粘贴到「动态码密钥」→ 应立刻显示 `✓ 已识别，保存后详情页会显示每 30 秒刷新的验证码`
3. 保存 → 详情页应出现动态码卡片，6 位数字 + 右侧倒计时进度条
4. 与网站/其他验证器 App 的码**逐位对比**
5. 等到剩余 5 秒以内 → 卡片整体转红；跨过边界 → 数字变化、进度条归零

### 7.2 签名（3 步）

1. 按 §2.1–2.2 生成 `.jks` 并填 `keystore.properties`
2. `gradle :app:assembleRelease` → 确认产物是 `app-release.apk`（**没有** `-unsigned` 后缀）
3. `apksigner verify --print-certs app\build\outputs\apk\release\app-release.apk` → 应打印你的证书指纹

### 7.3 排查表

| 现象 | 原因 |
|---|---|
| 粘贴后显示 `✗ 没认出这是有效的 TOTP 密钥` | 少了 `otpauth://` 前缀且不是纯 Base32；或粘贴的是 `hotp://` |
| 动态码卡片完全不出现 | 密钥保存成功但解析失败 → 回编辑页看是否有 `✓` 提示 |
| 验证码始终不对 | 手机系统时间不准（TOTP 依赖时间）→ 设置里打开「自动设置时间」 |
| release 仍是 `-unsigned` | `keystore.properties` 不在工程根目录，或口令含 `\` 等需转义字符 |
| `Keystore was tampered with` | PKCS12 下 `storePassword` 与 `keyPassword` 不一致 |

---

## 8. 已知限制

| # | 限制 | 说明 |
|---|---|---|
| 1 | 不支持 HOTP | 计数器型两步验证（少数老系统）用不了 |
| 2 | 不支持 Steam 的 5 位字母码 | Steam Guard 用的是自定义字符表 |
| 3 | TOTP 密钥明文存在条目里 | 和其它字段一样由 SQLCipher + 字段级 AES-GCM 保护，但备份文件里也是密文（整份文件加密），没有额外风险 |
| 4 | 没有"扫二维码" | 需要相机权限 + 二维码解码库，且多数网站都提供"手动输入"入口 |
| 5 | 签名私钥需要用户自己生成 | 见 §1.1：这是刻意的，不是我做不到 |

---

## 9. 当前状态

| 项 | 结果 |
|---|---|
| `:app:compileDebugKotlin` | BUILD SUCCESSFUL |
| `:app:compileDebugUnitTestKotlin` | BUILD SUCCESSFUL |
| 单元测试（JUnitCore 直跑 21 个类） | **OK (323 tests)**，0.816 s |
| `:app:lintDebug` | **No issues found.**（0 error / 0 warning） |
| `:app:assembleDebug` | BUILD SUCCESSFUL → 31.59 MB |
| `:app:assembleRelease`（R8） | BUILD SUCCESSFUL → 22.59 MB（未签名，因为无 `.jks`） |

归档：

- `dist/账号本-1.2.0-debug.apk`
- `dist/账号本-1.2.0-release-unsigned.apk`

---

## 10. 下一步

1. **你自己的 keystore 生成后，务必单独备份**（U 盘 / 密码管理器）。丢了它，这个应用的后续版本永远无法覆盖安装——只能换包名重新开始。
2. 真机回归测试（`docs/13` §8 的备份/指纹/审计 + 本文 §7 的 TOTP）。
3. 可选功能：拼音搜索、CSV 导出（给 Excel 看）、条目分享（加密链接）。
