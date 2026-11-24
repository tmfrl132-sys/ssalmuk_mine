plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 업데이트 설치가 되려면 매번 같은 키로 서명해야 함.
// GitHub Secrets(SIGNING_KEYSTORE_B64, SIGNING_PASSWORD)가 있으면 그 키로, 없으면 디버그 키로 서명.
val ksFile = System.getenv("SIGNING_KEYSTORE_FILE")

android {
    namespace = "life.ssalmuk.smsfwd"
    compileSdk = 35

    defaultConfig {
        applicationId = "life.ssalmuk.smsfwd"
        minSdk = 26
        targetSdk = 35
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "1.0.${System.getenv("GITHUB_RUN_NUMBER") ?: "0"}"
    }

    signingConfigs {
        if (ksFile != null) {
            create("release") {
                storeFile = file(ksFile)
                storePassword = System.getenv("SIGNING_PASSWORD")
                keyAlias = "smsfwd"
                keyPassword = System.getenv("SIGNING_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (ksFile != null) signingConfigs.getByName("release")
            else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.work:work-runtime:2.9.1")
    testImplementation("junit:junit:4.13.2")
}
