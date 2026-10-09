plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Original AB File Pro signing is intentionally external to the Safe Source.
// When the original keystore + credentials are present, release/debug builds are
// signed with the existing update-compatible certificate. Otherwise release stays unsigned.
val originalStorePassword = providers.gradleProperty("AB_FILE_PRO_STORE_PASSWORD").orNull
    ?: System.getenv("AB_FILE_PRO_STORE_PASSWORD")
val originalKeyPassword = providers.gradleProperty("AB_FILE_PRO_KEY_PASSWORD").orNull
    ?: System.getenv("AB_FILE_PRO_KEY_PASSWORD")
val originalKeyAlias = providers.gradleProperty("AB_FILE_PRO_KEY_ALIAS").orNull
    ?: System.getenv("AB_FILE_PRO_KEY_ALIAS")
    ?: "abfileprotest"
val originalKeyFile = file("ab-file-pro-test.jks")
val originalSigningReady = originalKeyFile.isFile &&
    !originalStorePassword.isNullOrBlank() &&
    !originalKeyPassword.isNullOrBlank()

android {
    signingConfigs {
        if (originalSigningReady) {
            create("original") {
                storeFile = originalKeyFile
                storePassword = originalStorePassword
                keyAlias = originalKeyAlias
                keyPassword = originalKeyPassword
            }
        }
    }

    namespace = "com.abfilepro.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.abfilepro.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 165
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        versionName = "2.0.0-alpha98-p13-clean-share-safety"

        vectorDrawables { useSupportLibrary = true }

        buildConfigField("String", "FAMILY_SYNC_BASE_URL", "\"https://family-sync-api-production.up.railway.app\"")

        // Device builds retain ARM64 only. CI can also package native x86_64
        // to exercise ML Kit without the emulator's ARM translation layer.
        ndk {
            abiFilters += setOf("arm64-v8a")
            if (providers.gradleProperty("AB_FILE_PRO_NATIVE_TEST").orNull == "true") {
                abiFilters += "x86_64"
            }
        }
    }

    buildTypes {
        debug {
            // Isolated install so testing never requires uninstalling the production app.
            applicationIdSuffix = ".p13sharetrial"
            if (originalSigningReady) {
                signingConfig = signingConfigs.getByName("original")
            }
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (originalSigningReady) {
                signingConfig = signingConfigs.getByName("original")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions { jvmTarget = "17" }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "META-INF/DEPENDENCIES",
            "META-INF/LICENSE",
            "META-INF/LICENSE.txt",
            "META-INF/NOTICE",
            "META-INF/NOTICE.txt"
        )
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    val composeBom = platform("androidx.compose:compose-bom:2025.01.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("androidx.work:work-runtime-ktx:2.10.0")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.camera:camera-core:1.4.1")
    implementation("androidx.camera:camera-camera2:1.4.1")
    implementation("androidx.camera:camera-lifecycle:1.4.1")
    implementation("androidx.camera:camera-view:1.4.1")

    implementation("com.google.mlkit:barcode-scanning:17.3.0")
    implementation("com.google.mlkit:face-detection:16.1.7")
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.mlkit:language-id:17.0.6")
    implementation("com.google.mlkit:translate:17.0.3")
    implementation("com.google.zxing:core:3.5.4")
    implementation("com.google.android.gms:play-services-mlkit-document-scanner:16.0.0")
    implementation("org.opencv:opencv:4.14.0")

    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    implementation("com.github.adaptech-cz.Tesseract4Android:tesseract4android:4.9.0")
}
