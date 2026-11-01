#Requires -Version 5.1
# VaultBook · 一键装到手机（Windows PowerShell）
# adb 优先用工程内的 .toolchain\android-sdk\platform-tools\adb.exe，没有就退回 PATH 里的 adb。

$ErrorActionPreference = "Stop"
$root = $PSScriptRoot

function Pause-Exit([int]$code) {
    Write-Host ""
    Read-Host "按回车关闭" | Out-Null
    exit $code
}

$adb = Join-Path $root ".toolchain\android-sdk\platform-tools\adb.exe"
if (-not (Test-Path $adb)) {
    $cmd = Get-Command adb -ErrorAction SilentlyContinue
    if ($cmd) { $adb = $cmd.Source }
}

Write-Host "============================================"
Write-Host "  VaultBook · 装到手机"
Write-Host "============================================"

if (-not $adb -or -not (Test-Path $adb)) {
    Write-Host "[X] 没找到 adb。" -ForegroundColor Red
    Write-Host "    工程内预期位置：$root\.toolchain\android-sdk\platform-tools\adb.exe"
    Write-Host "    也可以装个 platform-tools 并加入 PATH。"
    Pause-Exit 1
}

# 取 dist 里最新的安装包：优先 VaultBook-*.apk，其次任意 *-debug.apk，最后任意 *.apk
$dist = Join-Path $root "dist"
$apk = $null
foreach ($pattern in @("VaultBook-*.apk", "*-debug.apk", "*.apk")) {
    $apk = Get-ChildItem $dist -Filter $pattern -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1
    if ($apk) { break }
}

if (-not $apk) {
    Write-Host "[X] dist 目录里没有安装包，先构建：" -ForegroundColor Red
    Write-Host "    .toolchain\gradle\bin\gradle.bat :app:assembleRelease"
    Pause-Exit 1
}

Write-Host "APK : $($apk.Name)  ($([math]::Round($apk.Length / 1MB, 2)) MB)"
Write-Host "adb : $adb"
Write-Host ""
Write-Host "[1/3] 检查手机连接..."

& $adb start-server 2>&1 | Out-Null
$listing = & $adb devices 2>&1
$listing | ForEach-Object { Write-Host "      $_" }

$ready = @()
foreach ($line in $listing) {
    if ($line -match '^(\S+)\s+device$') { $ready += $Matches[1] }
}

if ($ready.Count -eq 0) {
    Write-Host ""
    Write-Host "[X] 没检测到已授权的设备。" -ForegroundColor Red
    Write-Host ""
    Write-Host "    请按顺序检查："
    Write-Host "      1. 手机「设置 - 关于手机」，连续点 7 次「版本号」，开启开发者选项"
    Write-Host "      2. 开发者选项里打开「USB 调试」"
    Write-Host "      3. 数据线连电脑，手机上弹出「允许 USB 调试吗」时点「允许」"
    Write-Host "      4. 数据线要能传数据（有些线只能充电）"
    Write-Host "      5. 连上后手机通知栏把 USB 用途改成「传输文件」"
    Write-Host ""
    Write-Host "    列表里状态是 unauthorized → 手机上没点允许；"
    Write-Host "    状态是 offline → 把线拔了重插一次。"
    Write-Host ""
    Write-Host "    手机连不上电脑也可以完全跳过 adb：" -ForegroundColor Yellow
    Write-Host "      把 dist\$($apk.Name) 传到手机（微信/QQ/数据线）"
    Write-Host "      ，然后在手机文件管理器里点它安装。"
    Pause-Exit 1
}

if ($ready.Count -gt 1) {
    Write-Host ""
    Write-Host "检测到 $($ready.Count) 台设备，用第一台：$($ready[0])"
}

Write-Host ""
Write-Host "[2/3] 正在安装（覆盖安装，不会丢数据）..."
$result = & $adb install -r $apk.FullName 2>&1
$result | ForEach-Object { Write-Host "      $_" }

if ($LASTEXITCODE -ne 0 -or ($result -join " ") -notmatch "Success") {
    Write-Host ""
    Write-Host "[X] 安装失败。常见原因：" -ForegroundColor Red
    Write-Host "    INSTALL_FAILED_UPDATE_INCOMPATIBLE - 手机上装过签名不同的版本，先卸载："
    Write-Host "      & `"$adb`" uninstall com.yg.vaultbook"
    Write-Host "    INSTALL_FAILED_USER_RESTRICTED - 小米/红米需在开发者选项里"
    Write-Host "      打开「USB 安装」并登录小米账号"
    Write-Host "    INSTALL_FAILED_INSUFFICIENT_STORAGE - 手机空间不足"
    Pause-Exit 1
}

Write-Host ""
Write-Host "[3/3] 完成。桌面上找「VaultBook」。" -ForegroundColor Green
Write-Host ""
Write-Host "第一次用建议走一遍："
Write-Host "  系统设置 -> 密码与账号 -> 自动填充服务 -> 选 VaultBook"
Write-Host "  打开 App -> 右下角 + -> 粘一段账号密码试试智能粘贴"
Pause-Exit 0
