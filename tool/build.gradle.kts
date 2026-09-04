import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.light.sdk)
}

// Private release keystore. It lives outside the repository - local/ is
// gitignored - so a fresh clone still builds, falling back to the shared key
// below. A fallback build is fine for local work and must never be shipped:
// anyone holding the shared key can forge an update to it.
val releaseKeystoreFile = rootProject.file("local/keystore.properties")
val releaseKeystore: Properties? =
    if (releaseKeystoreFile.exists()) {
        Properties().apply {
            releaseKeystoreFile.inputStream().use { load(it) }
        }
    } else {
        null
    }

android {
    compileSdk = rootProject.ext["compileSdk"] as Int

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = releaseKeystoreFile.parentFile
                    .resolve(releaseKeystore.getProperty("storeFile"))
                storePassword = releaseKeystore.getProperty("storePassword")
                keyAlias = releaseKeystore.getProperty("keyAlias")
                keyPassword = releaseKeystore.getProperty("keyPassword")
                enableV3Signing = true
                enableV4Signing = true
            }
        }
        create("lightsdkDev") {
            storeFile = file("../sdk/keys/lightsdk-dev.jks")
            storePassword = "android"
            keyAlias = "lightsdk-dev"
            keyPassword = "android"
            enableV3Signing = true
            enableV4Signing = true
        }
    }

    defaultConfig {
        minSdk = rootProject.ext["minSdk"] as Int
        targetSdk = rootProject.ext["targetSdk"] as Int

        manifestPlaceholders["sdkVersion"] = property("sdkVersion") as String
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("lightsdkDev")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.findByName("release")
                ?: signingConfigs.getByName("lightsdkDev")
        }
    }

    lint {
        warningsAsErrors = false
        error += "RestrictedApi"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.toVersion(rootProject.ext["jvmTarget"] as String)
        targetCompatibility = JavaVersion.toVersion(rootProject.ext["jvmTarget"] as String)
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.fromTarget(rootProject.ext["jvmTarget"] as String))
    }
}

dependencies {
    implementation(project(":sdk:client"))
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.kotlin.test)
    ksp(libs.androidx.room.compiler)
}
