plugins {
    id("com.android.application")
    // 自 AGP 9.0 起 Kotlin 支持已内置，不再需要 org.jetbrains.kotlin.android
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.mnnkit.app"

    // miuix 0.9.x 的 AAR 元数据硬性要求 minCompileSdk = 37
    compileSdk = 37

    // 编译 libmnnkitbridge.so 用的 NDK（与预编译 libMNN.so 的构建工具链一致）
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "com.mnnkit.app"

        // miuix-blur 模块硬编码 minSdk = 33（依赖 RenderEffect/AGSL RuntimeShader
        // 实现真实折射与高光，这两者分别是 API 31 / API 33 起可用）
        minSdk = 33
        targetSdk = 36
        versionCode = 2
        versionName = "0.2.0-beta1"

        ndk {
            abiFilters += "arm64-v8a"
        }

        // MNN LLM JNI 桥接层（app/src/main/cpp）。产出 libmnnkitbridge.so。
        // 关键：预编译 libMNN.so 的 DT_NEEDED 含 libc++_shared.so，必须用 c++_shared。
        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++17", "-fexceptions", "-frtti")
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON",
                    "-DMNN_SOURCE_ROOT=D:/dsh work region/reference/x/MNN-master",
                )
            }
        }
    }

    // 使用本地 keystore 为 release 包签名，使其可以直接安装。
    // 正式发布时应替换为自有签名密钥（见 README 的发布说明）。
    signingConfigs {
        create("local") {
            storeFile = file("mnnkit-local.keystore")
            storePassword = "mnnkit123"
            keyAlias = "mnnkit"
            keyPassword = "mnnkit123"
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            // R8 压缩：debug 包 46MB，其中 dex 占比最大（Compose + miuix + 图标集）
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("local")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // 原生库由 scripts/build-mnn-android.ps1 产出后放入 jniLibs。
    // 缺失时构建依然成功，应用会以降级模式运行（推理功能不可用）。
    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("src/main/jniLibs")
        }
    }

    lint {
        abortOnError = false
    }
}

dependencies {
    implementation(project(":core"))

    // ---- miuix：小米 MIUI/HyperOS 设计语言的 Compose 实现 ----
    // 坐标 top.yukonga.miuix.kmp，已实证发布于 Maven Central。
    // miuix-blur 即官方的液态玻璃实现（backdrop 捕获 + 高斯模糊 +
    // 双层定向光玻璃描边 + 陀螺仪视差）。
    implementation("top.yukonga.miuix.kmp:miuix-ui:0.9.4")
    implementation("top.yukonga.miuix.kmp:miuix-blur:0.9.4")
    implementation("top.yukonga.miuix.kmp:miuix-shader:0.9.4")
    implementation("top.yukonga.miuix.kmp:miuix-icons:0.9.4")
    implementation("top.yukonga.miuix.kmp:miuix-preference:0.9.4")
    implementation("top.yukonga.miuix.kmp:miuix-nav:0.9.4")

    // ---- 液态玻璃的真正来源 ----
    // 参考实现（KSuRoot）用的就是这个库，而不是 miuix-blur：
    //   io.github.kyant0:backdrop 提供 blur（毛玻璃）+ lens（折射与色散），
    //   其中 lens 是纯位移的 RuntimeShader，这才是"液态"观感的来源（需 API 33+，
    //   与本应用 minSdk = 33 吻合）。
    // 注意：它的 drawBackdrop 内部会 drawContent() 且带形状裁剪，
    //   所以玻璃修饰符必须挂在**不含子内容**的 Box 上，内容层要做它的兄弟节点。
    implementation("io.github.kyant0:backdrop-android:2.0.1")

    // ---- Compose 基础 ----
    // 说明：Compose 各 artifact 的版本由 miuix 传递引入的 CMP 1.12 决定，
    // 因此这里不再叠 compose-bom（叠了会与 miuix 要求的版本打架）。
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")

    // ---- 液态玻璃：照抄参考实现 KSuRoot 的选型 ----
    // miuix-blur 只有 blur + 描边，**没有 lens（折射）**；折射才是「液态」与「磨砂」的分界。
    // kyant0/backdrop 的效果链：vibrancy() → blur() → lens()，与 Apple 的做法一致。
    implementation("io.github.kyant0:backdrop-android:2.0.1")

    // ---- 动态取色（Monet）----
    // 参考实现用 material-kolor 生成主题色；配合 miuix 的 MonetSystem 模式，
    // 配色直接来自**壁纸**（Android 12+ 的 system_accent1）。
    // 没有它就只能用固定的 MIUI 蓝，拿不到「跟随壁纸」的效果。
    implementation("com.materialkolor:material-kolor:4.1.1")

    // ---- Material 3 + 图标 ----
    // 用 alpha22：miuix 0.9.4 自己依赖的就是它（见 `:app:dependencies` 的
    // `material3:1.3.1 -> 1.5.0-alpha22`），声明成别的版本会被 Gradle 改写。
    // MaterialExpressiveTheme / MotionScheme.expressive() 需要这个版本。
    implementation("androidx.compose.material3:material3:1.5.0-alpha22")
    implementation("androidx.compose.material:material-icons-core:1.7.8")
    implementation("androidx.compose.material:material-icons-extended:1.7.8")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation(kotlin("test"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
