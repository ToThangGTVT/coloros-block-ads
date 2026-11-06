plugins {
    alias(libs.plugins.android.library)
}

android {
    buildFeatures { viewBinding = true }
    compileSdk = 37
    defaultConfig {
        minSdk = 33
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    namespace = "com.utc.color.blockads.feature.querylog"
}

dependencies {
    implementation(libs.androidx.paging.runtime)
    implementation(project(":core:designsystem"))
    implementation(project(":core:common"))
    implementation(project(":core:data"))
    implementation(project(":core:filter"))
    implementation(libs.kotlinx.coroutines.android)
}
