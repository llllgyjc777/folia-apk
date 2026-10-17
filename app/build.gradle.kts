plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.folia.shell"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.folia.shell"
        minSdk = 24
        targetSdk = 34
        versionCode = 2
        versionName = "1.1.0"
    }

    // 固定签名：仓库内自带 keystore，保证每次云编译签名一致，可直接覆盖安装
    signingConfigs {
        create("fixed") {
            storeFile = file("../folia.keystore")
            storePassword = "folia123456"
            keyAlias = "folia"
            keyPassword = "folia123456"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("fixed")
        }
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

    // 用 legacy packaging 避免依赖清单冲突
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.webkit:webkit:1.11.0")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("com.google.android.material:material:1.12.0")
    // 锁屏 / 通知栏播放控制
    implementation("androidx.media:media:1.7.0")
}