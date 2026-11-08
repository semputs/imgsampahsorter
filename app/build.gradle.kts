plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val runNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "com.semput.imgsampahsorter"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.semput.imgsampahsorter"
        minSdk = 26
        targetSdk = 34
        versionCode = runNumber
        versionName = "1.0.$runNumber"
    }

    signingConfigs {
        // Personal sideloaded app: key lives in the repo so every build can update the last one.
        create("release") {
            storeFile = file("signing.jks")
            storePassword = "imgsampah"
            keyAlias = "imgsampah"
            keyPassword = "imgsampah"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}
