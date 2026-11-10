// DevReply Android SDK: the whole stack in Kotlin + Jetpack Compose (spec 05).
// No third-party dependencies: only AndroidX Compose and kotlinx.coroutines, which every Compose app already has.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    `maven-publish`
    signing
}

android {
    namespace = "com.devreply.sdk"
    compileSdk = 36
    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
        // Device tests of the library's own screens (src/androidTest): no API key, no network.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
    testOptions {
        unitTests.isReturnDefaultValues = true
        // The device-test APK: current target, so Android shows no "built for an older version" dialog.
        targetSdk = 36
    }
    publishing {
        singleVariant("release") {
            withSourcesJar()
            withJavadocJar()
        }
    }
}

// Two ways out, same artifact:
// - Maven Central: com.devreply:devreply-android:<version>, built with -Pcentral=true, signed with the release
//   key (~/.gradle/gradle.properties, never in the repo) into build/central-bundle, which
//   deploy/publish-maven-central.sh uploads to the Central Portal.
// - JitPack, from GitHub tags: com.github.Code-Axolot-Team:devreply-android:<tag> (apps already on it keep working).
val central = project.findProperty("central") == "true"

publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = if (central) "com.devreply" else "com.github.Code-Axolot-Team"
            artifactId = "devreply-android"
            version = (project.findProperty("version") as String?)?.takeIf { it != "unspecified" } ?: "0.3.2"
            afterEvaluate { from(components["release"]) }
            pom {
                name.set("DevReply")
                description.set("The in-app chat between your app's users and you. Kotlin + Jetpack Compose.")
                url.set("https://devreply.com")
                licenses { license { name.set("MIT"); url.set("https://opensource.org/license/mit") } }
                organization {
                    name.set("Code Axolot")
                    url.set("https://devreply.com")
                }
                developers {
                    developer {
                        id.set("code-axolot")
                        name.set("Code Axolot")
                        email.set("qa@codeaxolot.com")
                    }
                }
                scm {
                    url.set("https://github.com/Code-Axolot-Team/devreply-android")
                    connection.set("scm:git:https://github.com/Code-Axolot-Team/devreply-android.git")
                    developerConnection.set("scm:git:ssh://git@github.com/Code-Axolot-Team/devreply-android.git")
                }
                issueManagement {
                    system.set("GitHub")
                    url.set("https://github.com/Code-Axolot-Team/devreply-android/issues")
                }
            }
        }
    }
    repositories {
        maven {
            name = "centralBundle"
            url = uri(layout.buildDirectory.dir("central-bundle"))
        }
    }
}

signing {
    isRequired = central
    useGpgCmd()
    sign(publishing.publications)
}
tasks.withType<Sign>().configureEach { onlyIf { central } }

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

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.uiautomator)
}
