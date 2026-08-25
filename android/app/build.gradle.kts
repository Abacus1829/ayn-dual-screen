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

        /*
         * API 26 is the floor: launching an activity onto a chosen display needs
         * ActivityOptions.setLaunchDisplayId, which arrived in Oreo.
         *
         * `-PtestMinSdk=25` lowers it, and is for one thing only: getting a build onto an older
         * emulator to exercise the parts that have nothing to do with displays — the FTP server,
         * the certificate generation, the pairing handshake. BlueStacks ships Nougat by default,
         * which is API 25, and that is otherwise a wall.
         *
         * A build made this way is NOT shippable. The second-screen feature — the entire point of
         * the app — cannot work below Oreo, and on Nougat the app will crash the moment anything
         * reaches for a display API. Never pass this flag for a release.
         */
        minSdk = (project.findProperty("testMinSdk") as String?)?.toInt() ?: 26
        targetSdk = 34

        /*
         * Every change here needs a matching entry in CHANGELOG.md.
         *
         * The versionCode must go UP on every build anybody installs over an existing one: Android
         * refuses a lower one on top, and that has already cost a round of "the feature isn't there"
         * when the installed APK was simply the older build.
         *
         * The name lost its `-ftp` suffix here. It was chosen when the FTP server was the only new
         * thing in the release, and stopped being true several features ago.
         *
         * `-PtestVersionCode=14 -PtestVersionName=0.13.0` builds this same code labelled as an
         * older release, and exists for one job: testing the updater. You cannot exercise "an
         * update is available" without a build to be updated *from*, and hand-editing these two
         * lines before every test is how a wrong number eventually ships. Like `testMinSdk` above,
         * a build made this way is NOT shippable — it claims to be a version it is not.
         */
        versionCode = (project.findProperty("testVersionCode") as String?)?.toInt() ?: 23
        versionName = (project.findProperty("testVersionName") as String?) ?: "0.22.0"
    }

    signingConfigs {
        // Real signing comes from keystore.properties, which is yours and is never committed. When it
        // isn't there, fall back to the SDK's debug key so `assembleRelease` still produces something
        // installable to test on the Thor. That fallback cannot reach the Play Store by accident —
        // Google rejects anything signed with the debug key outright.
        create("upload") {
            /*
             * Sign with every scheme, because this app is sideloaded rather than installed from a
             * store, and the installer on the other end is not ours to predict.
             *
             * Gradle defaults to v2 only once minSdk is 24 or above, which is correct for a Play
             * Store upload and needlessly narrow for an APK someone downloads and taps. v1 keeps
             * older and third-party installers happy; v3 is what current Android prefers and what
             * key rotation would need later. The cost is a few kilobytes.
             */
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true

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

    /*
     * Unit tests, run on the desktop JVM by `gradlew test` — no device, no emulator.
     *
     * There is one thing here that genuinely earns a test, and it is the DER encoder in
     * stream/Asn1.kt: it touches no Android API, it is the kind of code that is either exactly right
     * or subtly wrong, and when it is wrong the symptom surfaces much later as a TLS failure against
     * somebody's PC. A test that builds a certificate and makes the platform parse it turns that
     * into a five-second answer.
     *
     * The rest of this app is UI and sockets, and is honestly tested by running it.
     */
    testImplementation("junit:junit:4.13.2")

    /*
     * org.json is an Android API, and the android.jar these tests compile against is stubs that
     * throw. The macro and layout formats are JSON end to end, so testing them at all needs a real
     * implementation on the test classpath -- this is the reference one, and it never ships in the
     * APK because it is testImplementation only.
     */
    testImplementation("org.json:json:20240303")
}
