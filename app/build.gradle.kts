plugins {
    alias(libs.plugins.android.application)
}

// ★★ 新模块只改这一行的包名即可（namespace / applicationId / Provider authority 都由它派生）
val moduleId = "com.wetype.enhance"

android {
    namespace = moduleId

    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = moduleId
        minSdk = 26
        targetSdk = 36

        // ★ 模块版本号：改功能时同步 +1（日志首行的 module version 取自这里）
        versionCode = 1
        versionName = "1.0.0"

        // 跨进程配置 Provider 的 authority 跟随 applicationId，改包名时不用再改清单
        manifestPlaceholders["configAuthority"] = "$moduleId.config"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            // ★ 签名：release 默认不签名，而未签名的 APK 系统会拒绝安装（"应用未安装"）。
            //   这里复用 debug 签名（模块自用足够），好处是 release 包能直接覆盖已安装的 debug 包。
            //   想换成自己的签名：在 android { signingConfigs { create("release") { ... } } } 里配置，
            //   再把下面这行指向它即可（换签名后需要先卸载旧包再安装）。
            signingConfig = signingConfigs.getByName("debug")

            // 打开 R8 **裁剪**（去掉没用到的 Kotlin 标准库等），但配合 -dontobfuscate **不混淆**：
            // 模块靠类名/包名工作（xposed_init / authority / 日志），混淆会直接失效。
            // 保留规则见 src/main/keepRules/rules.keep
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "src/main/keepRules/rules.keep",
            )
            optimization {
                enable = false
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        // 用于 BuildConfig.APPLICATION_ID / VERSION_NAME（框架内部使用）
        buildConfig = true
    }
}

dependencies {
    // Xposed API 82：仅编译期可见，运行时由 LSPosed 提供，绝不能打包进 APK。
    // jar 已随框架内置（app/libs），因此不联网也能打开 / 构建。
    compileOnly(files("libs/xposed-api-82.jar"))
}
