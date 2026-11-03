plugins {
    alias(libs.plugins.android.library)
}

android {
    compileSdk = 37
    defaultConfig {
        minSdk = 33
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    namespace = "com.utc.color.blockads.core.tunnel"
}

dependencies {
    implementation(project(":core:engine-bridge"))
    implementation(project(":core:common"))
    implementation(libs.kotlinx.coroutines.android)
}
