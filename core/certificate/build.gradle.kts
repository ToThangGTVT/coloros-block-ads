plugins { alias(libs.plugins.android.library) }

android {
    namespace = "com.utc.color.blockads.core.certificate"
    compileSdk = 37
    defaultConfig { minSdk = 33 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:data"))
    implementation(project(":core:engine-bridge"))
    implementation(libs.kotlinx.coroutines.android)
}
