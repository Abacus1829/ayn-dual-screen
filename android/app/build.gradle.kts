// Imported rather than written as java.util.Properties: inside the Kotlin DSL, `java` resolves to the
// project's java extension, so the fully qualified name doesn't compile here.
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.abacus.dualscreen"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.abacus.dualscreen"

        // API 26 is the floor: launching an activity onto a chosen display needs
        // ActivityOptions.setLaunchDisplayId, which arrived in Oreo.
        minSdk = 26
        targetSdk = 34

        versionCode = 6
        versionName = "0.6.0"
    }

    signingConfigs {
        // Real signing comes from keystore.properties, which is yours and is never committed. When it
        // isn't there, fall back to the SDK's debug key so `assembleRelease` still produces something
        // installable to test on the Thor. That fallback cannot reach the Play Store by accident —
        // Google rejects anything signed with the debug key outright.
        create("upload") {
            val properties = rootProject.file("keystore.properties")
            if (properties.exists()) {
                val config = Properties().apply { properties.inputStream().use { load(it) } }
                storeFile = file(config.getProperty("storeFile"))
                storePassword = config.getProperty("storePassword")
                keyAlias = config.getProperty("keyAlias")
                keyPassword = config.getProperty("keyPassword")
            } else {
                val debugKey = File(System.getProperty("user.home"), ".android/debug.keystore")
                if (debugKey.exists()) {
                    storeFile = debugKey
                    storePassword = "android"
                    keyAlias = "androiddebugkey"
                    keyPassword = "android"
                }
            }
        }
    }

    buildTypes {
        release {
            /*
             * R8 is off: this is a sideload build.
             *
             * Obfuscation existed to make a Play Store binary tedious to lift. A build you install
             * yourself gains nothing from it and pays for it twice — every crash arrives as one-letter
             * names that need a mapping.txt to read, and that file becomes one more thing to keep,
             * match to a build, and never publish by accident.
             *
             * Turn both back on if this ever goes to a store, and keep the mapping file with the
             * release it came from.
             */
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("upload")
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
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.0")
}
