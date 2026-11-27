plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
if (file("google-services.json").exists()) apply(plugin = "com.google.gms.google-services")

val laundryWorkerUrl = providers.gradleProperty("camasirWorkerUrl").orNull.orEmpty().trimEnd('/')
require(laundryWorkerUrl.isEmpty() || Regex("""https://[a-z0-9-]+\.[a-z0-9-]+\.workers\.dev""").matches(laundryWorkerUrl)) {
    "camasirWorkerUrl must be the HTTPS address of your Worker on workers.dev."
}

android {
    namespace = "com.camasirsarayi.app"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.camasirsarayi.app"
        minSdk = 24
        targetSdk = 36
        versionCode = 13
        versionName = "1.11"
        buildConfigField("boolean", "APPLE_SIGN_IN_ENABLED", (providers.gradleProperty("camasirAppleSignIn").orNull == "true").toString())
        buildConfigField("String", "LAUNDRY_WORKER_URL", "\"$laundryWorkerUrl\"")
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies {
    implementation("org.chromium.net:cronet-bundled:500.1.0")
    implementation("androidx.credentials:credentials:1.3.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.3.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation(platform("com.google.firebase:firebase-bom:34.14.1"))
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-firestore")
    implementation("com.google.firebase:firebase-functions")
    debugImplementation("com.google.firebase:firebase-appcheck-debug")
    releaseImplementation("com.google.firebase:firebase-appcheck-playintegrity")
}
