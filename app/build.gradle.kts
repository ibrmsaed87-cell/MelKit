import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

fun signingValue(name: String): String? = System.getenv(name)?.takeIf { it.isNotBlank() }
val releaseKeyPath = signingValue("MELKIT_KEYSTORE_PATH")
val configuredVersionCode = providers.environmentVariable("MELKIT_VERSION_CODE")
    .map { it.toInt() }.getOrElse(1)

android {
    namespace = "com.spinel.melkit"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "com.spinel.melkit"
        minSdk = 23
        targetSdk = 36
        versionCode = configuredVersionCode
        versionName = "0.1.0"
    }

    signingConfigs {
        create("permanentRelease") {
            if (releaseKeyPath != null) {
                storeFile = file(releaseKeyPath)
                storePassword = signingValue("MELKIT_STORE_PASSWORD")
                keyAlias = signingValue("MELKIT_KEY_ALIAS")
                keyPassword = signingValue("MELKIT_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("permanentRelease")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

// No unsigned release artifacts or accidental debug APKs for phone distribution.
val verifyPermanentSigning by tasks.registering {
    doLast {
        check(releaseKeyPath != null && file(releaseKeyPath).isFile) {
            "Use ci/build-android.sh with the original MelKit signing secret."
        }
        listOf("MELKIT_STORE_PASSWORD", "MELKIT_KEY_ALIAS", "MELKIT_KEY_PASSWORD").forEach {
            check(signingValue(it) != null) { "Missing required signing variable: $it" }
        }
    }
}

tasks.configureEach {
    if (name == "preReleaseBuild") dependsOn(verifyPermanentSigning)
    if (name == "assembleDebug" || name == "packageDebug") {
        doFirst { error("Distribute a release APK signed with the permanent MelKit key.") }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.06.01")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
}
