// DevReply Android SDK: the whole stack in Kotlin + Jetpack Compose (spec 05).
// No third-party dependencies: only AndroidX Compose and kotlinx.coroutines, which every Compose app already has.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    `maven-publish`
}

android {
    namespace = "com.devreply.sdk"
    compileSdk = 36
    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
    testOptions { unitTests.isReturnDefaultValues = true }
    publishing {
        singleVariant("release") { withSourcesJar() }
    }
}

// Published from GitHub tags through JitPack: com.github.Code-Axolot-Team:devreply-android:<tag>.
publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = "com.github.Code-Axolot-Team"
            artifactId = "devreply-android"
            version = (project.findProperty("version") as String?)?.takeIf { it != "unspecified" } ?: "0.3.1"
            afterEvaluate { from(components["release"]) }
            pom {
                name.set("DevReply")
                description.set("The in-app chat between your app's users and you. Kotlin + Jetpack Compose.")
                url.set("https://github.com/Code-Axolot-Team/devreply-android")
                licenses { license { name.set("MIT"); url.set("https://opensource.org/license/mit") } }
            }
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        explicitApi()
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    api(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)
    implementation(libs.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.org.json) // real org.json on the JVM (android.jar only has stubs)
}
