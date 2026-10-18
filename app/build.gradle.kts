plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.husam.usbprint"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.husam.usbprint"
        minSdk = 24
        targetSdk = 35
        versionCode = 3
        versionName = "1.2"
    }

    // Fixed signing key kept in the repo so every CI build can update the installed app.
    signingConfigs {
        create("fixed") {
            storeFile = file("usbprint.keystore")
            storePassword = "usbprint123"
            keyAlias = "usbprint"
            keyPassword = "usbprint123"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("fixed")
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
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
