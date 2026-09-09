plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val ciBuildNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1

android {
    namespace = "com.devcore.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.devcore.app"
        minSdk = 26
        // DevCore executes a downloaded local JDK/Gradle/Android build toolchain.
        // Android 10+ blocks execve from writable app storage for targetSdk >= 29,
        // so this developer-only IDE intentionally targets 28.
        targetSdk = 28
        versionCode = 1000 + ciBuildNumber
        versionName = "0.2.$ciBuildNumber"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.apache.commons:commons-compress:1.27.1")
    implementation("org.tukaani:xz:1.10")
}
