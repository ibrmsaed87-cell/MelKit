import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.gms.google-services")
}

fun signingValue(name: String): String? = System.getenv(name)?.takeIf { it.isNotBlank() }
val releaseKeyPath = signingValue("MELKIT_KEYSTORE_PATH")
val configuredVersionCode = providers.environmentVariable("MELKIT_VERSION_CODE")
    .map { it.toInt() }.getOrElse(3)
// Production release uses the verified MelKit units. CI explicitly selects test units for phone-test APKs.
val liveAds = providers.environmentVariable("MELKIT_LIVE_ADS").orElse("true").map {
    require(it == "true" || it == "false") { "MELKIT_LIVE_ADS must be true or false" }
    it == "true"
}.get()

android {
    namespace = "com.spinel.melkit"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "com.spinel.melkit"
        // The published Shizuku API 13.1.5 AAR requires API 24.
        minSdk = 24
        targetSdk = 36
        versionCode = configuredVersionCode
        versionName = "0.1.18"
        buildConfigField("boolean", "TEST_ADS", (!liveAds).toString())
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
        debug { buildConfigField("boolean", "TEST_ADS", "true") }
        release {
            signingConfig = signingConfigs.getByName("permanentRelease")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures {
        aidl = true
        compose = true
        buildConfig = true
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
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
    if (name == "bundleRelease") {
        doFirst { check(liveAds) { "Publishing a Release AAB requires MELKIT_LIVE_ADS=true." } }
    }
    if (name == "preReleaseBuild") dependsOn(verifyPermanentSigning)
    if (name == "assembleDebug" || name == "packageDebug") {
        doFirst { error("Distribute a release APK signed with the permanent MelKit key.") }
    }
}

dependencies {
    implementation("com.google.android.gms:play-services-ads:25.5.0")
    implementation("com.google.android.ump:user-messaging-platform:4.0.0")
    implementation(platform("com.google.firebase:firebase-bom:35.1.0"))
    implementation("com.google.firebase:firebase-messaging")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    val composeBom = platform("androidx.compose:compose-bom:2025.06.01")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    implementation("com.google.zxing:core:3.5.3")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
}


