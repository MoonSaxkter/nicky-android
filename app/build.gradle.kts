import com.android.build.gradle.internal.cxx.configure.gradleLocalProperties
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.chaquo.python") version "15.0.1" // << agrega versión
}

android {
    namespace = "com.example.android_app"        // ← debe coincidir con el package del código
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.android_app"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }

        // ← LEE local.properties (ojo al nombre: localProps, sin espacios)
        val localProps = gradleLocalProperties(rootDir, providers)

        // ← EXPONE CLAVES A BuildConfig
        buildConfigField("String", "ELEVEN_API_KEY", "\"${localProps.getProperty("ELEVEN_API_KEY", "")}\"")
        buildConfigField("String", "ELEVEN_VOICE", "\"${localProps.getProperty("ELEVEN_VOICE", "")}\"")
        buildConfigField("String", "OPENAI_API_KEY", "\"${localProps.getProperty("OPENAI_API_KEY", "")}\"")

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

    // Activa generación de BuildConfig y ViewBinding
    buildFeatures {
        buildConfig = true
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
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

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