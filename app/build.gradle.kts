import java.net.URI
import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 离线语音输入：sherpa-onnx（k2-fsa，Apache-2.0）的 Android AAR（静态链接 onnxruntime）。
// 文件太大不进仓库：构建时从 k2-fsa 的 GitHub release 下载到 app/libs/，核对 sha256（不对就删掉报错）。
val sherpaVer = "2.0.3"
val sherpaAar = file("libs/sherpa-onnx-static-link-onnxruntime-$sherpaVer.aar")
val sherpaSha = "7744a3a8f08f017d59c2d8ed8d9b700345561f5abe9f4e06dc6eaef3a0b977b4"
fun sha256Of(f: File): String {
    val md = MessageDigest.getInstance("SHA-256")
    f.inputStream().use { i -> val b = ByteArray(1 shl 16); while (true) { val n = i.read(b); if (n < 0) break; md.update(b, 0, n) } }
    return md.digest().joinToString("") { "%02x".format(it) }
}
if (!sherpaAar.isFile || sha256Of(sherpaAar) != sherpaSha) {
    sherpaAar.parentFile.mkdirs()
    val tmp = File(sherpaAar.path + ".part")
    val url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/v$sherpaVer/${sherpaAar.name}"
    println("downloading $url")
    URI(url).toURL().openStream().use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
    val got = sha256Of(tmp)
    if (got != sherpaSha) { tmp.delete(); throw GradleException("sherpa-onnx aar sha256 mismatch: $got") }
    sherpaAar.delete(); tmp.renameTo(sherpaAar)
}
android {
    namespace = "com.zhishi.timetable"
    compileSdk = 36
    defaultConfig {
        // 知时 AI：和知时（com.zhishi.timetable）是两个应用，可以同时安装；数据、设置、提醒、小部件完全分开
        applicationId = "com.zhishi.timetable.ai"
        minSdk = 26
        targetSdk = 35
        val run = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionCode = run
        versionName = "ai-$run"
        // 测试机（模拟器）是 x86：传 -PallAbi 时不过滤
        if (!project.hasProperty("allAbi")) ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
        // 预览版（非 main 分支的 CI 构建传 -Ppreview）：完全关掉应用内更新，不查、不提示、不下载正式版
        buildConfigField("boolean", "PREVIEW", if (project.hasProperty("preview")) "true" else "false")
        // 冒烟测试钩子（TestHooks）：debug 和 qa 打开，release 关
        buildConfigField("boolean", "HOOKS", "false")
    }
    signingConfigs {
        create("rel") {
            // 知时 AI 自己的签名密钥（和知时不同）：CI 从 Actions secret 还原，仓库里没有
            storeFile = file(System.getenv("KS_FILE") ?: "../zhishi-ai.p12")
            storePassword = System.getenv("KS_PASS") ?: ""
            keyAlias = "zhishi-ai"
            keyPassword = System.getenv("KS_PASS") ?: ""
            storeType = "pkcs12"
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("rel")
        }
        debug { buildConfigField("boolean", "HOOKS", "true") }
        // qa：和 release 一样经过 R8 混淆 / 优化（不可调试），但用调试签名、带测试钩子——冒烟测试用它在「混淆后的正式版代码」上跑崩溃排查
        create("qa") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            buildConfigField("boolean", "HOOKS", "true")
            matchingFallbacks += listOf("release")
        }
    }
    sourceSets.getByName("qa") {
        java.srcDir("src/debug/java")
        assets.srcDir("src/debug/assets")
        manifest.srcFile("src/debug/AndroidManifest.xml")
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    // so 在安装包里压缩存放（默认不压缩，sherpa-onnx 的 so 会让安装包大 2 倍多）
    packaging { jniLibs { useLegacyPackaging = true } }
    packaging { resources { excludes += listOf("META-INF/versions/9/OSGI-INF/MANIFEST.MF", "META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*") } }
    buildFeatures { compose = true; buildConfig = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.8" }
}
dependencies {
    val bom = platform("androidx.compose:compose-bom:2024.09.03")
    implementation(bom)
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("org.jsoup:jsoup:1.17.2")
    // 截图识别改由用户设置的 AI 看图模型做（AiRecognize.kt），不再带 ML Kit
    // 离线语音输入：sherpa-onnx（见文件开头）+ 解模型包（.tar.bz2）用的 Commons Compress（Apache-2.0）
    implementation(files(sherpaAar))
    implementation("org.apache.commons:commons-compress:1.24.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
