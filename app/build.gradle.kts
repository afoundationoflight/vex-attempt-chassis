plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.omnipolative.chassis"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.omnipolative.vexattempt"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "9.0-vex-attempt"
    }

    sourceSets["main"].kotlin.srcDirs("src/main/kotlin")

    // THE STORES SHIP COMPRESSED, AND THAT COSTS NOTHING.
    //
    // I had noCompress here on the reasoning that a compressed asset
    // has to be inflated before it can be mapped. True, and irrelevant:
    // assets inside an apk CANNOT be mmapped at all — AssetManager
    // hands you a stream, not an address — so the activity copies them
    // to filesDir on first launch either way. The inflate rides along
    // with a copy that was already happening.
    //
    // 32.7 MB uncompressed against 14.8 MB compressed, same end state,
    // and the download is half. Keeping them raw bought nothing and
    // cost 18 MB.

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
}
