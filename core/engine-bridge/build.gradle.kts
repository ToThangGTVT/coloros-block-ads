plugins {
    alias(libs.plugins.android.library)
}

android {
    compileSdk = 37
    defaultConfig {
        minSdk = 33
        consumerProguardFiles("consumer-rules.pro")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    namespace = "com.utc.color.blockads.core.enginebridge"
}

abstract class VerifyNativeCore : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val binaries: ConfigurableFileCollection

    @TaskAction
    fun verify() {
        check(binaries.files.all { it.isFile }) {
            "Missing Rust native libraries. Run scripts/build-native.sh first."
        }
    }
}

val verifyNativeCore =
    tasks.register<VerifyNativeCore>("verifyNativeCore") {
        binaries.from(
            listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86").map {
                layout.projectDirectory.file("src/main/jniLibs/$it/libblockads_core.so")
            }
        )
    }

tasks.named("preBuild") { dependsOn(verifyNativeCore) }
