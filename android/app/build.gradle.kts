plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "dev.unrabbit.app"
    compileSdk = 35
    defaultConfig {
        applicationId = "dev.unrabbit.app"
        minSdk = 28
        targetSdk = 35
        versionCode = 17
        versionName = "0.8.0-dev"
        testInstrumentationRunner = "android.test.InstrumentationTestRunner"
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests.isIncludeAndroidResources = true }
    lint { abortOnError = true }
}
dependencies {
    implementation(project(":core"))
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    // Keep the verified SDK-35 camera stack pinned; upgrading it is a separate compatibility check.
    implementation("androidx.camera:camera-camera2:1.4.2")
    implementation("androidx.camera:camera-lifecycle:1.4.2")
    implementation("androidx.camera:camera-view:1.4.2")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
}
