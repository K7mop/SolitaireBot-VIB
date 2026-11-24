plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.retq.solitairebot"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.retq.solitairebot"
        minSdk = 26
        targetSdk = 35
        versionCode = 902
        versionName = "0.9.2-VIB"
    }
    buildTypes {
        release { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies {
    implementation(project(":core"))
    testImplementation("junit:junit:4.13.2")
}
