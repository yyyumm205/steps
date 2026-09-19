import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

val localProperties = Properties().apply {
    val source = rootProject.file("local.properties")
    if (source.isFile) source.inputStream().use(::load)
}

fun localBuildConfigString(name: String, defaultValue: String = ""): String {
    val value = localProperties.getProperty(name, defaultValue)
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
    return "\"$value\""
}

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.nexthci.ringfitness"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.nexthci.ringfitness.steps"
        minSdk = 30
        targetSdk = 36
        versionCode = 41
        versionName = "0.7.7-phone-time"
        manifestPlaceholders["appLabel"] = "步数采集"

        buildConfigField("String", "PUBLIC_DATA_DIRECTORY", "\"RingFitnessSteps\"")

        buildConfigField("String", "STUDY_UPLOAD_LINK", localBuildConfigString("ringfitness.uploadLink"))
        buildConfigField(
            "String",
            "ACTIVITY_UPLOAD_LINK",
            localBuildConfigString("ringfitness.activityUploadLink"),
        )
        buildConfigField("String", "STUDY_AUTHORIZATION_URL", localBuildConfigString("ringfitness.authorizationUrl"))
        buildConfigField("String", "STUDY_ENROLLMENT_CODE", localBuildConfigString("ringfitness.enrollmentCode"))

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        create("recoveryQa") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".recoveryqa"
            versionNameSuffix = "-qa"
            manifestPlaceholders["appLabel"] = "步数采集·恢复验证"
            matchingFallbacks += "debug"
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    // Separate installation and app-private data for deliberate process/network failures.
    testBuildType = if (providers.gradleProperty("ringfitness.recoveryQa").orNull == "true") "recoveryQa" else "debug"
    sourceSets.getByName("recoveryQa") {
        java.srcDir("src/debug/java")
        manifest.srcFile("src/debug/AndroidManifest.xml")
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
    }
}

dependencies {
    implementation(files("libs/polar-ble-sdk.aar"))
    // Required at runtime by the local Polar SDK AAR (androidx.core.util.Pair).
    implementation("androidx.core:core-ktx:1.9.0")
    implementation("androidx.browser:browser:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-rx3:1.10.2")
    implementation("com.google.protobuf:protobuf-javalite:3.20.0")
    implementation("com.google.flatbuffers:flatbuffers-java:25.2.10")
    implementation("commons-io:commons-io:2.11.0")
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.okhttp3:okhttp:3.14.9")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")
    implementation("com.google.code.gson:gson:2.11.0")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
}
