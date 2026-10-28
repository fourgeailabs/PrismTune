plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

import java.io.FileInputStream
import java.util.Properties

// Release signing is read from untracked local.properties (never committed).
// Keys: prismtune.storeFile, prismtune.storePassword, prismtune.keyAlias, prismtune.keyPassword
val localProps = Properties()
val localPropsFile = rootProject.file("local.properties")
if (localPropsFile.exists()) {
    FileInputStream(localPropsFile).use { localProps.load(it) }
}

android {
    namespace = "com.fourgeailabs.prismtune"
    compileSdk = 36

    signingConfigs {
        create("release") {
            if (localPropsFile.exists()) {
                storeFile = file(localProps.getProperty("prismtune.storeFile"))
                storePassword = localProps.getProperty("prismtune.storePassword")
                keyAlias = localProps.getProperty("prismtune.keyAlias")
                keyPassword = localProps.getProperty("prismtune.keyPassword")
            }
        }
    }

    defaultConfig {
        applicationId = "com.fourgeailabs.prismtune"
        minSdk = 29
        targetSdk = 36
        versionCode = 42
        versionName = "1.5.9"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "META-INF/LICENSE.md"
            excludes += "META-INF/NOTICE.md"
        }
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.coil.compose)
    implementation(libs.okhttp)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)
    implementation(libs.media3.ui)
    implementation(libs.zxing.core)
    implementation(libs.security.crypto)

    testImplementation(libs.junit)
    // 1.0.7: real org.json for unit tests — without this, the
    // android.jar org.json stubs throw "not mocked" in tests
    // that parse Plex JSON responses. Test-only, not shipped.
    testImplementation(libs.json)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
