plugins {
    // AGP 9 ships built-in Kotlin support, so org.jetbrains.kotlin.android is
    // not applied. See https://kotl.in/gradle/agp-built-in-kotlin
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// google-services.json is never committed: CI writes it from a variable, and a
// contributor without one still gets a working build with push reported as
// unavailable. The plugin fails the build when the file is missing, so it is
// only applied when the file is there.
if (file("google-services.json").exists()) {
    apply(plugin = libs.plugins.google.services.get().pluginId)
}

/** A build setting from `-P`, `ORG_GRADLE_PROJECT_<name>` or gradle.properties. */
fun setting(name: String, default: String = ""): String = providers.gradleProperty(name).getOrElse(default)

fun String.quoted(): String = "\"" + replace("\\", "\\\\").replace("\"", "\\\"") + "\""

android {
    namespace = "cz.peelco.jolt"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "cz.peelco.jolt"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()

        // Overridden in CI via -Pandroid.injected.version.code/name.
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "JOLT_DEFAULT_SERVER_URL", setting("JOLT_DEFAULT_SERVER_URL", "https://jolt.example.com/api/v1").quoted())
        buildConfigField("String", "RELAY_ALLOWED_HOSTS", setting("JOLT_RELAY_ALLOWED_HOSTS").quoted())
        buildConfigField("long", "PLAY_INTEGRITY_CLOUD_PROJECT", "${setting("JOLT_PLAY_INTEGRITY_CLOUD_PROJECT", "0").toLongOrNull() ?: 0L}L")
        // Firebase can also be initialised from these when google-services.json
        // is absent, so a CI job can inject them as plain variables.
        buildConfigField("String", "FIREBASE_PROJECT_ID", setting("JOLT_FIREBASE_PROJECT_ID").quoted())
        buildConfigField("String", "FIREBASE_APPLICATION_ID", setting("JOLT_FIREBASE_APPLICATION_ID").quoted())
        buildConfigField("String", "FIREBASE_API_KEY", setting("JOLT_FIREBASE_API_KEY").quoted())
        buildConfigField("String", "FIREBASE_SENDER_ID", setting("JOLT_FIREBASE_SENDER_ID").quoted())
    }

    signingConfigs {
        create("release") {
            val keystore = providers.environmentVariable("ANDROID_KEYSTORE_FILE").orNull
            if (keystore != null) {
                storeFile = file(keystore)
                storePassword = providers.environmentVariable("ANDROID_KEYSTORE_PASSWORD").get()
                keyAlias = providers.environmentVariable("ANDROID_KEY_ALIAS").get()
                keyPassword = providers.environmentVariable("ANDROID_KEY_PASSWORD").get()
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            // Only wired up when the keystore env vars are present (CI / release machine).
            signingConfig =
                signingConfigs.getByName("release").takeIf {
                    providers.environmentVariable("ANDROID_KEYSTORE_FILE").isPresent
                }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }

    lint {
        warningsAsErrors = false
        abortOnError = true
        checkDependencies = false
        // Version bumps are a deliberate, separate change; lint should not
        // fail a build because a newer artifact was published overnight.
        disable += setOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion")
    }

    packaging {
        resources {
            excludes +=
                setOf(
                    "/META-INF/{AL2.0,LGPL2.1}",
                    "/META-INF/INDEX.LIST",
                    "/META-INF/DEPENDENCIES",
                )
        }
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    // --- compose ---
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    debugImplementation(libs.compose.ui.tooling)

    // --- androidx ---
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)

    // --- networking ---
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.serialization.json)
    implementation(libs.coroutines.android)
    implementation(libs.coroutines.play.services)

    // --- push ---
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)
    implementation(libs.play.integrity)

    // --- camera + qr ---
    implementation(libs.camera.core)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)
    implementation(libs.mlkit.barcode.scanning)
    implementation(libs.zxing.core)

    // --- unit tests ---
    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.turbine)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.junit)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}
