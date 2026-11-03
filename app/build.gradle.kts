plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.androidx.baselineprofile)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.utc.color.blockads"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.utc.color.blockads"
        minSdk = 33
        targetSdk = 37
        versionCode = 5
        versionName = "0.4.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }
}

baselineProfile { automaticGenerationDuringBuild = false }

dependencies {
    baselineProfile(project(":baselineprofile"))
    implementation(libs.androidx.profileinstaller)
    implementation(project(":core:common"))
    implementation(project(":core:designsystem"))
    implementation(project(":core:data"))
    implementation(project(":core:engine-bridge"))
    implementation(project(":core:tunnel"))
    implementation(project(":core:filter"))
    implementation(project(":feature:home"))
    implementation(project(":feature:querylog"))
    implementation(project(":feature:filters"))
    implementation(project(":feature:apps"))
    implementation(project(":feature:settings"))
    implementation(project(":feature:certificate"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.core)
    implementation(libs.hilt.android)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.work.runtime)
    ksp(libs.hilt.compiler)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

// The profile plugin maps its generated manifest to release. Restore the test-only seed activity.
androidComponents {
    finalizeDsl { extension ->
        extension.sourceSets.named("nonMinifiedRelease") {
            manifest.srcFile("src/nonMinifiedRelease/AndroidManifest.xml")
        }
    }
}
