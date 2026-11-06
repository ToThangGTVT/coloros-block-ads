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
    namespace = "com.utc.color.blockads.core.designsystem"
}

dependencies {
    api(libs.androidx.activity)
    api(libs.androidx.lifecycle.runtime.ktx)
    api(libs.androidx.recyclerview)
    api(libs.material)
    implementation(libs.androidx.dynamicanimation)
    implementation(project(":core:common"))
    api(libs.mpandroidchart)
}
