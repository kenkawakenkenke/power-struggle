plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.kenkawamoto.powerstruggle"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.kenkawamoto.powerstruggle"
        minSdk = 33
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
    }

    // Release signing reads POWER_STRUGGLE_* from ~/.gradle/gradle.properties. Without them,
    // assembleRelease still works but produces an unsigned APK.
    val storeFilePath = providers.gradleProperty("POWER_STRUGGLE_STORE_FILE").orNull
    signingConfigs {
        if (storeFilePath != null) {
            create("release") {
                storeFile = file(storeFilePath)
                storePassword = providers.gradleProperty("POWER_STRUGGLE_STORE_PASSWORD").get()
                keyAlias = providers.gradleProperty("POWER_STRUGGLE_KEY_ALIAS").get()
                keyPassword = providers.gradleProperty("POWER_STRUGGLE_KEY_PASSWORD").get()
            }
        }
    }

    buildTypes {
        release {
            // Keep R8 off: Shizuku starts ShellService by class name.
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    buildFeatures {
        compose = true
        aidl = true
        buildConfig = true
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
}
