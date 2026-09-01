plugins { id("com.android.application") }

android {
    namespace = "com.dadir.phoneactivity"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.dadir.phoneactivity"
        minSdk = 28
        targetSdk = 35
        versionCode = 21
        versionName = "1.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.work:work-runtime:2.11.2")
    implementation("com.google.android.gms:play-services-auth:22.0.0")
    implementation("androidx.biometric:biometric:1.1.0")
}
