// 顶层构建脚本：微信输入法增强（LSPosed 模块）
// 只声明 Android 应用插件；Xposed API 以本地 jar 形式 compileOnly 引入（见 app/build.gradle.kts）
plugins {
    alias(libs.plugins.android.application) apply false
}
