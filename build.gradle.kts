// sdk-android/build.gradle.kts（root）
plugins {
    alias(libs.plugins.android.library)     apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android)      apply false
    alias(libs.plugins.ksp)                 apply false
    id("com.google.gms.google-services")    version "4.4.1" apply false
}
