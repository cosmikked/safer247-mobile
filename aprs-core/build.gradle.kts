plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.safer247.aprs"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(libs.kotlinx.coroutines.android) 
    testImplementation(libs.kotlin.test.junit) 
    testImplementation(libs.junit) 
}