plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
}

// Credenciais em local.properties (gitignored) — ver local.properties.example
fun localOr(key: String, fallback: String): String {
    val f = rootProject.file("local.properties")
    if (!f.exists()) return fallback
    val line = f.readLines()
        .map { it.trim() }
        .firstOrNull { it.startsWith("$key=") && !it.startsWith("#") }
        ?: return fallback
    val value = line.substringAfter("=").trim()
    return value.ifBlank { fallback }
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
}

android {
    namespace = "com.folderbackup.agent"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.folderbackup.agent"
        minSdk = 26
        targetSdk = 35
        versionCode = 6
        versionName = "0.6.0-auto-pair"
    }

    flavorDimensions += "env"

    productFlavors {
        create("local") {
            dimension = "env"
            // USB: adb reverse → 127.0.0.1 | Wi-Fi: IP do PC
            buildConfigField(
                "String",
                "API_BASE_URL",
                "\"${localOr("api.local.url", localOr("api.base.url", "http://127.0.0.1:8080"))}\"",
            )
            buildConfigField(
                "String",
                "API_TOKEN",
                "\"${localOr("api.local.token", localOr("api.token", "12345678"))}\"",
            )
            buildConfigField("String", "ENV_NAME", "\"local\"")
        }
        create("prod") {
            dimension = "env"
            // VPS via nginx — APK aponta para /webhook (sem /api no final)
            buildConfigField(
                "String",
                "API_BASE_URL",
                "\"${localOr("api.prod.url", "https://n8n.jediael.uk/webhook")}\"",
            )
            buildConfigField(
                "String",
                "API_TOKEN",
                "\"${localOr("api.prod.token", localOr("api.token", "12345678"))}\"",
            )
            buildConfigField("String", "ENV_NAME", "\"prod\"")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
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
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.documentfile)

    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)

    debugImplementation(libs.androidx.ui.tooling.preview)
}
