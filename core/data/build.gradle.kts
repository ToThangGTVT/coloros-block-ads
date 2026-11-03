plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
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
    namespace = "com.utc.color.blockads.core.data"
}

dependencies {
    implementation(project(":core:common"))
    api(libs.androidx.room.paging)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.paging)
    ksp(libs.androidx.room.compiler)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

ksp { arg("room.schemaLocation", "$projectDir/schemas") }
