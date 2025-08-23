plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.chaquo.python") version "15.0.1" // << agrega versión
}

android {
    namespace = "com.example.android_app"        // ← debe coincidir con el package del código
    compileSdk = 35

    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.android_app" // ← igual que arriba
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }

        // Requerido por Chaquopy
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }

    // Activa ViewBinding para no depender de R en imports manuales
    buildFeatures {
        viewBinding = true
    }

    compileOptions {
        // Si te da guerra Java 21, bájalo a 17
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    kotlinOptions {
        jvmTarget = "21"
    }
}

chaquopy {
    defaultConfig {
        pip {
            install("requests")
            install("python-dotenv")
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.activity:activity-ktx:1.9.2")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // Unit tests
    testImplementation("junit:junit:4.13.2")

    // Instrumented tests (androidTest)
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}

// Alinear Kotlin con Java 21
kotlin {
    jvmToolchain(21)
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    kotlinOptions {
        jvmTarget = "21"
    }
}