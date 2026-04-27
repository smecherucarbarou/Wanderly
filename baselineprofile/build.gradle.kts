plugins {
    alias(libs.plugins.android.test)
}

kotlin {
    jvmToolchain(17)
}

android {
    namespace = "com.novahorizon.wanderly.baselineprofile"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    targetProjectPath = ":app"
}

dependencies {
    implementation(libs.androidx.benchmark.macro.junit4)
    implementation(libs.androidx.junit)
    implementation(libs.androidx.uiautomator)
}
