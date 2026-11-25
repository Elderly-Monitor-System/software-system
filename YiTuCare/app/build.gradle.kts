import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

val locationProperties = Properties().apply {
    val local = rootProject.file("local.properties")
    if (local.isFile) local.reader(Charsets.UTF_8).use { load(it) }
}

fun locationConfig(name: String): String {
    val property = "YITU_$name"
    val value = (providers.gradleProperty(property).orNull
        ?: locationProperties.getProperty(property, "")).trim()
    val escaped = value.replace("\\", "\\\\").replace("\"", "\\\"")
        .replace("\n", "\\n").replace("\r", "\\r")
    return "\"$escaped\""
}

android {
    buildFeatures { buildConfig = true }

    namespace = "com.zhihuyitu.care"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.zhihuyitu.care"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        buildConfigField("String", "LOCATION_BASE_URL", locationConfig("LOCATION_BASE_URL"))
        buildConfigField("String", "LOCATION_DEVICE_ID", locationConfig("LOCATION_DEVICE_ID"))
        buildConfigField("String", "LOCATION_STAY_RADIUS_METERS", locationConfig("LOCATION_STAY_RADIUS_METERS"))
        buildConfigField("String", "AMAP_JS_KEY", locationConfig("AMAP_JS_KEY"))
        buildConfigField("String", "AMAP_ORIGIN", locationConfig("AMAP_ORIGIN"))
        buildConfigField("String", "AMAP_SERVICE_HOST", locationConfig("AMAP_SERVICE_HOST"))
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            val phoneTest = providers.gradleProperty("YITU_LOCATION_PHONE_TEST").orNull
                ?: locationProperties.getProperty("YITU_LOCATION_PHONE_TEST", "true")
            buildConfigField("boolean", "LOCATION_PHONE_TEST", phoneTest.trim().equals("true", ignoreCase = true).toString())
            buildConfigField("String", "AMAP_SECURITY_CODE", locationConfig("AMAP_SECURITY_CODE"))
        }
        release {
            buildConfigField("boolean", "LOCATION_PHONE_TEST", "false")
            buildConfigField("String", "AMAP_SECURITY_CODE", "\"\"")
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.activity.ktx)
    implementation(libs.appcompat)
    implementation(libs.constraintlayout)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.ext.junit)
    implementation("androidx.navigation:navigation-fragment:2.10.2")
    implementation("androidx.navigation:navigation-ui:2.10.2")
}
