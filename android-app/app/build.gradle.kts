plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.meo"
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "com.meo"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        debug {
            // Emulators are x86_64, and an emulator that cannot load the WebRTC
            // native library is useless for development. Debug keeps every ABI.
            ndk {
                abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
            }
        }
        release {
            // Disable minification for now - causes crashes
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // ADR 0008 requires an explicit ABI decision rather than shipping
            // whatever the AAR happens to carry. The WebRTC x86 and x86-64
            // libraries are 12.6 MB and 16.1 MB uncompressed and exist for
            // emulators; no shipping Android phone needs them. Release carries
            // the two ARM ABIs only.
            ndk {
                abiFilters += listOf("armeabi-v7a", "arm64-v8a")
            }
        }
    }

    // Don't split APKs - create one universal APK
    splits {
        abi {
            isEnable = false
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

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.5"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    testOptions {
        unitTests.all { test ->
            // Unit tests fork their own JVM, so a -D on the Gradle command line
            // does not reach them by itself. GoldenFixtureTest documents
            // -Dmeo.fixtures.write=true as the way to regenerate the committed
            // protocol fixtures; this is what makes that instruction true.
            test.systemProperty(
                "meo.fixtures.write",
                System.getProperty("meo.fixtures.write") ?: "false"
            )
        }
    }
}

dependencies {
    // Core Android
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.2")
    implementation("androidx.activity:activity-compose:1.8.1")

    // Jetpack Compose - Updated BOM for Kotlin 1.9.20 compatibility
    implementation(platform("androidx.compose:compose-bom:2024.02.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // Lifecycle & ViewModel
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.6.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.6.2")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // CameraX. Capture is owned by the camera foreground service; the Compose
    // screen only supplies an optional preview surface.
    // 1.6.x is compiled with Kotlin 2.1 metadata and would force an unrelated
    // Compose/Kotlin migration. 1.5.3 is the maintained compatible stable line.
    val cameraXVersion = "1.5.3"
    implementation("androidx.camera:camera-core:$cameraXVersion")
    implementation("androidx.camera:camera-camera2:$cameraXVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraXVersion")
    implementation("androidx.camera:camera-view:$cameraXVersion")
    implementation("androidx.lifecycle:lifecycle-service:2.6.2")

    // Network Service Discovery (NSD) - built into Android, no extra dep needed

    // Control plane. ADR 0001 chooses versioned JSON over protobuf; the one
    // property that matters is safe handling of unknown fields, which comes
    // from ignoreUnknownKeys plus the golden fixtures in protocol/.
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.0")

    // Media plane. Pinned exactly per ADR 0008: unprefixed webrtc-sdk artifact,
    // never a dynamic version, never the package-relocated LiveKit variant.
    // The AAR ships no license file of its own, so its generated notice set is
    // vendored under licenses/ and linked from THIRD_PARTY_NOTICES.md. Changing
    // this coordinate without updating both is a release failure.
    implementation("io.github.webrtc-sdk:android:144.7559.09")

    // QR scanning. ZXing is self-contained: no Play Services, so the APK works
    // on devices without Google services too.
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")

    // Tests
    testImplementation("junit:junit:4.13.2")

    // Debug
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
