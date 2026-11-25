# 智护忆途 YiTu Care 安卓端

安卓原生应用，使用 Java 和 XML。应用 ID 为 com.zhihuyitu.care，包含首页、定位、报警和我的四个模块。

## 打开和构建

1. 用 Android Studio 打开包含 settings.gradle.kts 的 YiTuCare 目录。
2. 安装工程所需的 Android SDK API 37。同步时使用 Gradle Wrapper 和 JDK 25。
3. 选一台设备点击 Run。也可在 Windows PowerShell 执行以下命令。

    .\gradlew.bat :app:assembleDebug :app:testDebugUnitTest

## 本机配置

Android Studio 会创建 local.properties。保留其中的 sdk.dir，需要高德地图时再填写自己的开发配置。

    YITU_AMAP_JS_KEY=
    YITU_AMAP_SECURITY_CODE=

高德配置留空时可以构建应用，地图页会显示配置提示。Debug 默认使用手机实测位置。需要改用设备定位时，按联调环境填写 YITU_LOCATION_BASE_URL 和 YITU_LOCATION_DEVICE_ID，并设 YITU_LOCATION_PHONE_TEST=false。

提交源码时保留 .gitignore。带密钥的调试 APK 用于本机联调。公开分发时使用 YITU_AMAP_SERVICE_HOST 配置服务端代理，安全密钥保存在服务器。
