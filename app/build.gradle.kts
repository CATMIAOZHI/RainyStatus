import java.io.File

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt.android)
}

android {
    namespace = "com.rainy.status"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.rainy.status"
        minSdk = 31
        targetSdk = 35
        versionCode = 2
        versionName = "1.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        create("release") {
            // CI 环境没有 release.jks，自动 fallback 到 debug keystore
            // Release workflow 通过 Secret 注入 release.jks
            val keystoreFile = rootProject.file("release.jks")
            if (keystoreFile.exists()) {
                storeFile = keystoreFile
                // 凭据必须通过环境变量注入；缺失或为空时直接报错，不提供任何隐式 fallback
                // 注意：GitHub Actions 中未设置的 secret 会被替换为空字符串（非 null），
                // 因此用 takeIf { isNotBlank() } 同时防御 null 和空字符串
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                    ?.takeIf { it.isNotBlank() }
                    ?: throw GradleException("KEYSTORE_PASSWORD env var not set or empty — cannot sign release")
                keyAlias = System.getenv("KEYSTORE_ALIAS")
                    ?.takeIf { it.isNotBlank() }
                    ?: throw GradleException("KEYSTORE_ALIAS env var not set or empty — cannot sign release")
                keyPassword = System.getenv("KEY_PASSWORD")
                    ?.takeIf { it.isNotBlank() }
                    ?: throw GradleException("KEY_PASSWORD env var not set or empty — cannot sign release")
            } else {
                // 仅在 CI 环境中 fallback 到 debug keystore（用于编译/资源完整性验证）
                // 本地构建缺少 release.jks 时直接报错，避免静默生成 debug 签名的 Release APK
                if (System.getenv("CI") != null) {
                    val debugKeystore = file("${System.getProperty("user.home")}/.android/debug.keystore")
                    storeFile = debugKeystore
                    storePassword = "android"
                    keyAlias = "androiddebugkey"
                    keyPassword = "android"
                } else {
                    throw GradleException(
                        "release.jks 不存在，且当前不是 CI 环境。\n" +
                        "正式 Release 构建需要 release.jks 密钥库文件。\n" +
                        "如需本地验证编译，请设置环境变量 CI=true 或使用 assembleDebug。"
                    )
                }
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    androidResources {
        // Android 13+ 应用级语言设置：根据 values-* 目录自动生成 locales_config
        generateLocaleConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

// Force ARM64 AAPT2 in Proot environment (local only; GitHub Actions x86_64 uses default)
if (System.getProperty("os.arch") == "aarch64") {
    configurations.all {
        resolutionStrategy.eachDependency {
            if (requested.group == "com.android.tools.build" && requested.name == "aapt2") {
                useTarget("com.android.tools.build:aapt2:${'$'}{requested.version}:linux-aarch64")
            }
        }
    }
}

dependencies {

    // Core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)

    // Navigation
    implementation(libs.androidx.navigation.compose)

    // Network
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)

    // DataStore
    implementation(libs.androidx.datastore.preferences)

    // DI (Hilt + KSP)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    // Test
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}