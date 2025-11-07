plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.joel.indriveaudiofix"

    // Android Gradle Plugin 8.1.1 es compatible con Gradle 8.0+
    compileSdk = 34

    defaultConfig {
        applicationId = "dev.joel.indriveaudiofix"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Reutiliza debug signing para facilitar instalación manual
            signingConfig = signingConfigs.getByName("debug")
        }
        debug {
            // Mantener sin minify para inspección en LSPosed
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    // Evita empaquetar recursos innecesarios (limpia el APK aún más)
    packagingOptions {
        resources {
            excludes += setOf(
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/DEPENDENCIES"
            )
        }
    }
}

dependencies {
    // Xposed API (solo tiempo de compilación; no se empaqueta)
    compileOnly("de.robv.android.xposed:api:82")
    compileOnly("de.robv.android.xposed:api:82:sources")
}