plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.camasirsarayi.hafta2"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.camasirsarayi.hafta2"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0-hafta2"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies {
    constraints { implementation("com.google.errorprone:error_prone_annotations:2.18.0") }
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
}


