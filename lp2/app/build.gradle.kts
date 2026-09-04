import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

/**
 * The core, shared with the LP3 tool.
 *
 * The files still live once, under `tool/src/main/kotlin`. They cannot move
 * somewhere neutral, because the Light SDK's plugin forbids a consumer module
 * from declaring custom source directories - so the tool must keep its sources
 * where they are, and this build reaches across to them.
 *
 * Copied rather than referenced because the ten screen files import
 * `com.thelightphone.*`, which does not exist here, and they have to be left
 * behind. A Sync task filters properly; an Android source set has no filter
 * that can be scoped to a single source directory.
 */
val sharedCoreDir = layout.buildDirectory.dir("sharedCore")

val syncSharedCore = tasks.register<Sync>("syncSharedCore") {
    description = "Copies the SDK-free core out of the LP3 tool."
    from("../../tool/src/main/kotlin") {
        exclude(
            "com/outofthewhale/wordoflight/*Screen.kt",
            "com/outofthewhale/wordoflight/ToolEntryPoint.kt",
        )
    }
    into(sharedCoreDir)
}

tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(syncSharedCore) }
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    dependsOn(syncSharedCore)
}

// Private release keystore. It lives outside the repository - local/ is
// gitignored - so a fresh clone still builds, falling back to the shared key
// below. A fallback build is fine for local work and must never be shipped:
// anyone holding the shared key can forge an update to it.
val releaseKeystoreFile = rootProject.file("../local/keystore.properties")
val releaseKeystore: Properties? =
    if (releaseKeystoreFile.exists()) {
        Properties().apply {
            releaseKeystoreFile.inputStream().use { load(it) }
        }
    } else {
        null
    }

android {
    namespace = "com.outofthewhale.wordoflight.lp2"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.outofthewhale.wordoflight.lp2"
        // The LP3 tool targets minSdk 34, which no Light Phone 2 can install.
        // 26 covers Android 8.0 and up and is comfortably above what anything
        // shared here needs: DataStore wants 21, Keystore AES/GCM wants 23.
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "1.2.0"
    }

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
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            // Signed with the private release keystore when it is present.
            // The debug-key fallback keeps a fresh clone building; it is not
            // a distribution key, since anyone can produce an "update"
            // signed the same way.
            signingConfig = signingConfigs.findByName("release")
                ?: signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        compose = true
    }

    sourceSets {
        getByName("main") {
            // The core arrives via syncSharedCore below rather than by pointing
            // straight at the tool's directory: that would drag in its ten
            // SDK-bound screens too, and an Android source set does not offer a
            // filter that can be scoped to one source directory.
            kotlin.srcDirs("src/main/kotlin", sharedCoreDir)

            // Assets need no filtering, so they are shared directly - 17.3 MB
            // of KJV text, lexicon and concordance, not duplicated into the
            // repository a second time.
            assets.srcDirs("src/main/assets", "../../tool/src/main/assets")
        }
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
    val composeBom = platform("androidx.compose:compose-bom:2026.03.01")
    implementation(composeBom)

    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")

    implementation("androidx.datastore:datastore-preferences:1.1.7")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    implementation("io.ktor:ktor-client-core:3.4.2")
    implementation("io.ktor:ktor-client-okhttp:3.4.2")
    implementation("io.ktor:ktor-client-content-negotiation:3.4.2")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.4.2")
}
