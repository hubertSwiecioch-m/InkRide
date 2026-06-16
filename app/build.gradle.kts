import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.ksp)
    id("org.jetbrains.kotlinx.kover")
}

// Release signing config.
// Values are sourced (in priority order) from environment variables (used by CI)
// or a local, git-ignored `keystore.properties` file. When nothing is configured
// the release build falls back to debug signing so a local `assembleRelease` still works.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        FileInputStream(keystorePropertiesFile).use { load(it) }
    }
}

fun signingValue(envKey: String, propKey: String): String? =
    System.getenv(envKey) ?: keystoreProperties.getProperty(propKey)

android {
    namespace = "com.speedevand.inkride"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.speedevand.inkride"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            val storeFilePath = signingValue("KEYSTORE_FILE", "storeFile")
            if (storeFilePath != null) {
                storeFile = file(storeFilePath)
                storePassword = signingValue("KEYSTORE_PASSWORD", "storePassword")
                keyAlias = signingValue("KEY_ALIAS", "keyAlias")
                keyPassword = signingValue("KEY_PASSWORD", "keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Use the release keystore when configured, otherwise fall back to debug
            // signing so the project still builds without secrets present.
            signingConfig = signingConfigs.getByName("release")
                .takeIf { it.storeFile != null }
                ?: signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

dependencies {
    implementation(project(":core:domain"))
    implementation(project(":core:presentation"))
    implementation(project(":core:design-system"))
    implementation(project(":core:database"))
    implementation(project(":feature:dashboard:presentation"))
    implementation(project(":feature:history:data"))
    implementation(project(":feature:history:presentation"))
    implementation(project(":feature:settings:data"))
    implementation(project(":feature:settings:presentation"))
    implementation(project(":feature:tracking:data"))
    implementation(project(":feature:ble:data"))
    implementation(project(":feature:ble:presentation"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.mudita.mmd)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    
    implementation(libs.koin.android)
    implementation(libs.koin.androidx.compose)

    testImplementation(libs.junit)
    testImplementation(libs.junit.jupiter.api)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.assertk)
    testImplementation(libs.turbine)
    testImplementation(libs.mockito.kotlin)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
