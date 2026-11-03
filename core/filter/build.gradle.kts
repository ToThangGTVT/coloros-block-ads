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
    namespace = "com.utc.color.blockads.core.filter"
}

dependencies {
    implementation(project(":core:data"))
    implementation(project(":core:common"))
    implementation(project(":core:engine-bridge"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.work.runtime)
}
