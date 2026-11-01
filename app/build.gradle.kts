import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// ── 发布签名 ────────────────────────────────────────────────────────────
// 密钥与口令都不进版本库：keystore.properties 与 .jks 都在 .gitignore 里。
// 文件不存在时下面会退化成"未签名 release"，构建不会失败 —— 手上没有密钥的人
// 也能正常编译调试，有密钥的人才签得出可发布的包。
//
// keystore.properties 内容（四项，缺一不可）：
//   storeFile=../keystore/vaultbook-release.jks   ← 相对 app/ 目录
//   storePassword=...
//   keyAlias=vaultbook
//   keyPassword=...
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) {
        keystorePropsFile.inputStream().use { load(it) }
    }
}

/** 把 properties 里的 storeFile 解析成真实文件：先按工程根目录，再按 app/ 目录。 */
fun resolveStoreFile(raw: String?): File? {
    if (raw.isNullOrBlank()) return null
    val byRoot = rootProject.file(raw)
    if (byRoot.exists()) return byRoot
    val byApp = project.file(raw)
    if (byApp.exists()) return byApp
    return null
}

val resolvedStoreFile = resolveStoreFile(keystoreProps.getProperty("storeFile"))
val hasSigningConfig = resolvedStoreFile != null &&
    listOf("storePassword", "keyAlias", "keyPassword")
        .all { !keystoreProps.getProperty(it).isNullOrBlank() }

// 配了 properties 但 .jks 找不到时，静默退回"未签名"很容易让人以为签名生效了，
// 所以在配置阶段显式喊一声。
if (keystorePropsFile.exists() && !hasSigningConfig) {
    logger.warn(
        "[VaultBook] keystore.properties 存在，但 storeFile/口令不完整或 .jks 找不到（" +
            keystoreProps.getProperty("storeFile") +
            "）→ release 将产出未签名 APK。"
    )
}

android {
    namespace = "com.yg.vaultbook"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.yg.vaultbook"
        minSdk = 26          // Autofill Framework 起始版本
        targetSdk = 35
        versionCode = 100
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }

        // 只打 arm64-v8a 一个 ABI。
        //
        // SQLCipher 的 libsqlcipher.so 每个 ABI 约 4–6 MB。四个 ABI 全打进去是
        // 20.65 MB，而你手机上只有一份会被加载：
        //   · arm64-v8a     2017 年以后的手机（现在绝大多数设备）
        //   · armeabi-v7a   老的 32 位机 —— v1.7.1 起不再支持
        //   · x86 / x86_64  只有模拟器用得上
        // 要给 32 位老机装时，把 "armeabi-v7a" 加回这个列表即可，
        // 代价是包体 +3.86 MB。
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    // 必须写在 buildTypes 之前：buildTypes.release 里要按名字取用这个配置，
    // 反过来写会抛 UnknownDomainObjectException（release 尚不存在）。
    signingConfigs {
        if (hasSigningConfig) {
            create("release") {
                storeFile = resolvedStoreFile
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
                // v1(JAR) 从 minSdk 26 起完全多余，关掉能减小包体；
                // v2 是底线，v3 支持将来轮换签名密钥，v4 用于增量安装。
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // 有正式密钥就用它；没有就用 debug 密钥签。
            //
            // 不这样写的话 assembleRelease 产出的是 *-unsigned.apk，装不上 ——
            // 而 release 包经过 R8 压缩，比 debug 包小得多（debug 不压缩、
            // 保留全部调试信息、dex 按 16 个分包）。自己装用的话，
            // debug 密钥签名的 release 包完全能用，只是不能上架。
            signingConfig = if (hasSigningConfig) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
        debug {
            // 加后缀以便与发布版并存调试；发布前如不需要可去掉
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    testOptions {
        unitTests.all { it.useJUnit() }
    }
}

// Room 导出的 schema 存到 app/schemas，便于以后写迁移和评审表结构变更
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)

    debugImplementation(libs.androidx.compose.ui.tooling)

    // ── W1 数据层：Room + SQLCipher ───────────────────────────────
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.sqlcipher)

    // ── W6：生物识别解锁（已随"去主密码"改造删除）──────────────────
    // 指纹解锁在没有主密码的前提下没有意义：库本来就不上锁。
    // 相关代码（core/biometric/、Gateway 的五个方法、Settings 的开关）已全部删除，
    // 依赖也一并去掉，省下 biometric + fragment 两个库的体积。
    // implementation(libs.androidx.biometric)
    // implementation(libs.androidx.fragment)

    // 纯 JVM 单测（不依赖 Android 运行时，加密层与工具层全部可测）
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // Android 自带的 org.json 在单测里是空实现（方法体被替换成 return null / 0），
    // 备份编解码的测试必须用真实实现，否则断言全拿到 null 却不报错。
    testImplementation(libs.json)

    // ── 后续里程碑预留 ────────────────────────────────────────────
    // implementation(libs.androidx.datastore.preferences)
    // implementation(libs.androidx.work.runtime.ktx)
}
