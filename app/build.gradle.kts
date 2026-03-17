import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val localSigningProps = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) {
        file.inputStream().use { load(it) }
    }
}

fun signingValue(name: String): String? {
    return providers.gradleProperty(name).orNull
        ?: providers.environmentVariable(name).orNull
        ?: localSigningProps.getProperty(name)
}

val signingStoreFile = signingValue("SIGNING_STORE_FILE")
val signingKeyAlias = signingValue("SIGNING_KEY_ALIAS")
val signingStorePassword = signingValue("SIGNING_STORE_PASSWORD")
val signingKeyPassword = signingValue("SIGNING_KEY_PASSWORD")

val hasReleaseSigning = listOf(
    signingStoreFile,
    signingKeyAlias,
    signingStorePassword,
    signingKeyPassword,
).all { !it.isNullOrBlank() }

val isReleaseTaskRequested = gradle.startParameter.taskNames.any {
    it.contains("Release", ignoreCase = true)
}

android {
    namespace = "io.github.liuran001.fakebluetoothconnect"
    compileSdk = 35

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(requireNotNull(signingStoreFile))
                storePassword = requireNotNull(signingStorePassword)
                keyAlias = requireNotNull(signingKeyAlias)
                keyPassword = requireNotNull(signingKeyPassword)
            }
        }
    }

    defaultConfig {
        applicationId = "io.github.liuran001.fakebluetoothconnect"
        minSdk = 24
        targetSdk = 35
        versionCode = 2
        versionName = "1.1.1"
    }

    buildTypes {
        release {
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            } else if (isReleaseTaskRequested) {
                throw GradleException(
                    "Missing release signing config. Provide SIGNING_STORE_FILE, SIGNING_KEY_ALIAS, SIGNING_STORE_PASSWORD and SIGNING_KEY_PASSWORD via keystore.properties, Gradle properties or environment variables.",
                )
            }
            isMinifyEnabled = true
            isShrinkResources = true
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

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

dependencies {
    compileOnly("de.robv.android.xposed:api:82")
}
