plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
val signingStorePassword = providers.gradleProperty("SIGNING_STORE_PASSWORD")
    .orElse(providers.environmentVariable("SIGNING_STORE_PASSWORD"))
    .orElse("")
    .get()
val signingKeyPassword = providers.gradleProperty("SIGNING_KEY_PASSWORD")
    .orElse(providers.environmentVariable("SIGNING_KEY_PASSWORD"))
    .orElse("")
    .get()

android { namespace = "com.phonetv.phone"; compileSdk = 36
    defaultConfig { applicationId = "com.phonetv.phone"; minSdk = 26; targetSdk = 35; versionCode = 4; versionName = "1.1.11" }
    signingConfigs {
        create("release") {
            storeFile = rootProject.file("sll_test")
            storePassword = signingStorePassword
            keyAlias = "key0"
            keyPassword = signingKeyPassword
        }
    }
    buildTypes { getByName("release") { signingConfig = signingConfigs.getByName("release") } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    buildFeatures { compose = true }
}
kotlin { jvmToolchain(17) }
dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
}
