plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.moqandroid"
    compileSdk = 33

    defaultConfig {
        applicationId = "com.example.moqandroid"
        minSdk = 29
        targetSdk = 33
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // Latest available on Maven Central: 0.2.21.
    implementation("dev.moq:moq:0.2.18")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
