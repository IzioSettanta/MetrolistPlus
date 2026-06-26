plugins {
    id("com.android.library")
}

android {
    namespace = "com.metrolist.audioboost"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(libs.timber)
    implementation(libs.datastore)
    implementation(libs.hilt)
}
