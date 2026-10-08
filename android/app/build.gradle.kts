import java.util.Properties

plugins {
    id("com.android.application")
    id("com.google.gms.google-services")
}

android {
    namespace = "com.juxtapo.kdpager"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.juxtapo.kdpager"
        minSdk = 26
        targetSdk = 36
        versionCode = 16
        versionName = "0.6.5"
    }

    // Release signing comes from android/keystore.properties (never committed):
    //   storeFile=/abs/path/release.jks  storePassword=…  keyAlias=…  keyPassword=…
    val ksFile = rootProject.file("keystore.properties")
    signingConfigs {
        create("release") {
            if (ksFile.exists()) {
                val p = Properties().apply { ksFile.inputStream().use { load(it) } }
                storeFile = file(p.getProperty("storeFile"))
                storePassword = p.getProperty("storePassword")
                keyAlias = p.getProperty("keyAlias")
                keyPassword = p.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            // dev build: WebView devtools + logs on ROMs that hide them. Never hand this one out —
            // anyone with USB access could read the KD session from a debuggable WebView.
            isDebuggable = true
        }
        release {
            isMinifyEnabled = false
            isDebuggable = false
            signingConfig = if (ksFile.exists()) signingConfigs.getByName("release") else null
        }
    }
}

// no silent unsigned release: say exactly what is missing
tasks.matching { it.name == "assembleRelease" || it.name == "bundleRelease" }.configureEach {
    doFirst {
        if (!rootProject.file("keystore.properties").exists())
            throw GradleException("android/keystore.properties is missing — release builds need your own keystore (see README, 'Release build')")
    }
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:5.5.0")
    implementation("androidx.browser:browser:1.8.0")
    implementation(platform("com.google.firebase:firebase-bom:34.4.0"))
    implementation("com.google.firebase:firebase-messaging")
}
