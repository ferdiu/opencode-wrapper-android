plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "it.ferdiu.opencodewrapper"
    compileSdk = 37

    defaultConfig {
        applicationId = "it.ferdiu.opencodewrapper"
        minSdk = 26 // Foreground service types + notification channels need 26+
        targetSdk = 35
        versionCode = 5
        versionName = "0.3.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(
                org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
            )
        }
    }

    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.appcompat:appcompat:1.8.0")
    implementation("com.google.android.material:material:1.14.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.2")
    implementation("androidx.coordinatorlayout:coordinatorlayout:1.3.0")
    implementation("androidx.lifecycle:lifecycle-service:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("androidx.webkit:webkit:1.17.0")
    implementation("androidx.security:security-crypto:1.1.0")

    // Networking: OkHttp handles both plain REST calls and the SSE event stream.
    implementation("com.squareup.okhttp3:okhttp:5.5.0")
    implementation("com.squareup.okhttp3:okhttp-sse:5.5.0")

    // JSON parsing for OpenCode API payloads.
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    // Backup path for reconnect nudges if the OS kills the foreground service.
    implementation("androidx.work:work-runtime-ktx:2.11.2")

    // Android Auto car-screen app (projected from the phone; no phone UI changes).
    implementation("androidx.car.app:app:1.7.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:5.5.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
}
