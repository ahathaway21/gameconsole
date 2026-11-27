plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// The app's screen is the same health.html used on the website, bundled into the APK.
val copyWeb = tasks.register<Copy>("copyWeb") {
    from(rootProject.file("../health.html"))
    into(layout.buildDirectory.dir("webassets"))
}

android {
    namespace = "app.healthprofiler"
    compileSdk = 34

    defaultConfig {
        applicationId = "app.healthprofiler"
        minSdk = 26
        targetSdk = 34
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "1.0." + (System.getenv("GITHUB_RUN_NUMBER") ?: "0")
    }

    signingConfigs {
        // Fixed key so each new build installs as an update over the previous one.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        getByName("debug") { signingConfig = signingConfigs.getByName("debug") }
    }

    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("webassets"))

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

tasks.named("preBuild") { dependsOn(copyWeb) }

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.webkit:webkit:1.11.0")
}
