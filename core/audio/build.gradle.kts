plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.kotlin.serialization)
}

import java.util.Properties
import java.io.FileInputStream

// Выбор архитектур сборки через -PabiFilter=arm64-v8a[,x86_64] (см. build_debug.sh --abi).
val abiFilterProp = (project.findProperty("abiFilter") as String?)
    ?.split(",")
    ?.map { it.trim() }
    ?.filter { it.isNotEmpty() }

android {
    namespace = "com.binaural.core.audio"
    compileSdk = 34

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")

        // NDK конфигурация - все архитектуры (или только выбранные через -PabiFilter)
        ndk {
            abiFilters += if (abiFilterProp.isNullOrEmpty()) {
                listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
            } else {
                abiFilterProp
            }
        }
        
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17 -O3 -ffast-math -funroll-loops"
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DANDROID_TOOLCHAIN=clang"
                )
            }
        }
    }
    
    // Используем NDK, установленный в системе
    ndkVersion = "29.0.14206865"

    // НОВОЕ: нужен BuildConfig.DEBUG в модуле core:audio
    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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

    kotlinOptions {
        jvmTarget = "17"
    }
    
    // CMake конфигурация
    externalNativeBuild {
        cmake {
            path = File("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

dependencies {
    // AndroidX
    implementation(libs.androidx.core.ktx)

    // Hilt
    implementation(libs.bundles.hilt)
    ksp(libs.hilt.compiler)

    // Coroutines
    implementation(libs.bundles.coroutines)

    // DateTime
    implementation(libs.kotlinx.datetime)

    // Serialization
    implementation(libs.kotlinx.serialization.json)

    // Testing
    testImplementation(libs.bundles.testing)
    androidTestImplementation(libs.bundles.android.testing)
}
