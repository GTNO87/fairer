import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Load signing credentials from keystore.properties (excluded from Git).
// If the file doesn't exist the release build is assembled unsigned — useful
// for CI environments that don't need a deployable APK.
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) load(keystorePropsFile.inputStream())
}

android {
    namespace = "com.gtno.fairer"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.gtno.fairer"
        minSdk = 26
        targetSdk = 34
        versionCode = 6
        versionName = "1.1.4"
    }

    signingConfigs {
        if (keystorePropsFile.exists()) {
            create("release") {
                storeFile     = rootProject.file(keystoreProps["storeFile"] as String)
                storePassword = keystoreProps["storePassword"] as String
                keyAlias      = keystoreProps["keyAlias"] as String
                keyPassword   = keystoreProps["keyPassword"] as String
            }
        }
    }

    buildTypes {
        release {
            if (keystorePropsFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = true
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

    buildFeatures {
        viewBinding  = true
        buildConfig  = true
    }

    applicationVariants.all {
        val variant = this
        variant.outputs
            .map { it as com.android.build.gradle.internal.api.BaseVariantOutputImpl }
            .forEach { output ->
                output.outputFileName = "fairer-${variant.versionName}.apk"
            }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.work:work-runtime:2.9.0")
    implementation("org.bouncycastle:bcprov-jdk15on:1.70")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
