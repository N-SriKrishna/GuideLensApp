plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")  // Apply WITHOUT version number
}

android {
    namespace = "com.example.guidelensapp"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.guidelensapp"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        ndk {
            abiFilters.addAll(listOf("arm64-v8a", "x86_64"))
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    applicationVariants.all {
        outputs.all {
            val output = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
            val versionName = defaultConfig.versionName
            val versionCode = defaultConfig.versionCode
            val buildType = buildType.name

            // Custom APK name: GuideLens-v1.0-release.apk
            output.outputFileName = "GuideLens-v${versionName}-${buildType}.apk"

            // Alternative with version code and date:
            // val date = SimpleDateFormat("yyyyMMdd").format(Date())
            // output.outputFileName = "GuideLens-${versionCode}-${date}-${buildType}.apk"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}


// OPTIONAL: Compose Compiler Configuration
        composeCompiler {
            enableStrongSkippingMode = true
        }

        dependencies {
            // Core
            implementation("androidx.core:core-ktx:1.12.0")
            implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
            implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
            implementation("androidx.activity:activity-compose:1.8.2")

            // Compose
            val composeBom = platform("androidx.compose:compose-bom:2024.02.00")
            implementation(composeBom)
            implementation("androidx.compose.ui:ui")
            implementation("androidx.compose.ui:ui-graphics")
            implementation("androidx.compose.ui:ui-tooling-preview")
            implementation("androidx.compose.material3:material3")
            debugImplementation("androidx.compose.ui:ui-tooling")

            // CameraX
            implementation("androidx.camera:camera-core:1.3.1")
            implementation("androidx.camera:camera-camera2:1.3.1")
            implementation("androidx.camera:camera-lifecycle:1.3.1")
            implementation("androidx.camera:camera-view:1.3.1")

            // ONNX Runtime
            implementation("com.microsoft.onnxruntime:onnxruntime-android:1.17.0")

            // Permissions
            implementation("com.google.accompanist:accompanist-permissions:0.34.0")

            // Coroutines
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
        }

