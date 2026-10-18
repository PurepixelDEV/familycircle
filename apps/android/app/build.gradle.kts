// AGP 9 ships built-in Kotlin support, so `org.jetbrains.kotlin.android` is intentionally NOT applied.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    // TODO(release): the final applicationId is pending the project owner's approval – it can never
    //  change after the first public release. namespace (Kotlin package / R class) is independent of it.
    namespace = "org.familycircle.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "org.familycircle.android"
        minSdk = 26
        // TODO(targetSdk 37): Android 17 requires the ACCESS_LOCAL_NETWORK runtime permission for LAN
        //  connections (self-hosted servers on 192.168.x.x etc.). Add the permission + runtime flow
        //  before raising targetSdk. See docs/research/android-platform-2026-10.md.
        targetSdk = 36
        versionCode = 4
        versionName = "0.3.0"
    }

    // Release signing only when the CI provides a keystore (see RELEASE.md). Local release builds stay
    // unsigned; no key material ever lives in the repository.
    val signingStore = System.getenv("FC_SIGNING_STORE_FILE")
    if (!signingStore.isNullOrBlank()) {
        signingConfigs {
            create("release") {
                storeFile = file(signingStore)
                storePassword = System.getenv("FC_SIGNING_STORE_PASSWORD")
                keyAlias = System.getenv("FC_SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("FC_SIGNING_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            // Installs next to a release build; testers can tell both apart by name and version.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            // Label "FamilyCircle Debug": src/debug/res/values/strings.xml overrides app_name.
        }
        release {
            if (!signingStore.isNullOrBlank()) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // F-Droid: do not embed the (encrypted, Google-only readable) dependency metadata blob.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    lint {
        // MapLibre ships Timber's lint checks; the app deliberately logs through its own Logger
        // (android.util.Log, no coordinates/tokens) and does not use Timber.
        disable += "LogNotTimber"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.work.runtime.ktx)

    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    // Map (MapLibre Native, BSD-2; no Google Play Services) and QR scanning (CameraX + ZXing core,
    // Apache-2.0; no ML Kit).
    implementation(libs.maplibre.android)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.zxing.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver3)
}
