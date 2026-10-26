import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// The signing key never lives in the repo. CI passes it in environment variables; local builds read
// an untracked keystore.properties (storeFile, storePassword, keyAlias, keyPassword) at the repo root.
// With neither, debug builds use the default debug key and release APKs come out unsigned.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun signingValue(env: String, prop: String): String? =
    System.getenv(env)?.takeIf { it.isNotEmpty() } ?: keystoreProps.getProperty(prop)
val signing = listOf(
    "QB_KEYSTORE_FILE" to "storeFile", "QB_KEYSTORE_PASSWORD" to "storePassword",
    "QB_KEY_ALIAS" to "keyAlias", "QB_KEY_PASSWORD" to "keyPassword",
).associate { (env, prop) -> prop to signingValue(env, prop) }
// All four or nothing: a half-configured key would fail the build with a vague "missing property".
val signingComplete = signing.values.all { it != null }
if (!signingComplete && signing.values.any { it != null }) {
    logger.warn("Quietboard: signing is missing ${signing.filterValues { it == null }.keys}; building release unsigned.")
}

android {
    namespace = "dev.larryjones.quietboard"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.larryjones.quietboard"
        minSdk = 29
        targetSdk = 34
        versionCode = 22
        versionName = "1.14.1"
    }

    // One shared key so APKs built anywhere (GitHub Actions, your PC) can update each other.
    signingConfigs {
        if (signingComplete) {
            create("shared") {
                storeFile = rootProject.file(signing.getValue("storeFile")!!)
                storePassword = signing["storePassword"]
                keyAlias = signing["keyAlias"]
                keyPassword = signing["keyPassword"]
            }
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfigs.findByName("shared")?.let { signingConfig = it }
        }
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("shared")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    // No Google Play dependency metadata blob in the APK.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true
    }
}

// Zero third-party runtime libraries: only the Kotlin stdlib and the Android framework.
dependencies {
    testImplementation("junit:junit:4.13.2")
    // Renders the real views to PNG on the JVM (test-only, never shipped).
    testImplementation("org.robolectric:robolectric:4.13")
}
