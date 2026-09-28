// The demo app: a blank app with one button that opens DevReply (same as sdk/swift/Example).
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.codeaxolot.devreply.example"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.codeaxolot.devreply.example"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Your app's Android public key from the DevReply dashboard (safe to ship): -Pdevreply.pk=pk_…,
        // or `devreply.pk=pk_…` in ~/.gradle/gradle.properties.
        val pk = (project.findProperty("devreply.pk") as String?) ?: "pk_YOUR_PUBLIC_KEY"
        buildConfigField("String", "DEVREPLY_PK", "\"$pk\"")
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":devreply"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.activity.compose)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.uiautomator)
}
