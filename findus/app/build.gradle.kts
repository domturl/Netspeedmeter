plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.android)
  alias(libs.plugins.kotlin.compose)
}

// Only apply the Google Services plugin once you've added app/google-services.json
// (see README.md). The project still compiles without it so you can look around first.
if (file("google-services.json").exists()) {
  apply(plugin = libs.plugins.google.services.get().pluginId)
}

android {
  namespace = "app.findus"
  compileSdk = 36

  defaultConfig {
    applicationId = "app.findus"
    minSdk = 26
    targetSdk = 36
    versionCode = 1
    versionName = "1.0"
  }

  buildTypes {
    release {
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  buildFeatures { compose = true }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
  implementation(platform(libs.androidx.compose.bom))
  implementation(platform(libs.firebase.bom))
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.lifecycle.service)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.play.services)
  implementation(libs.play.services.location)
  implementation(libs.firebase.auth)
  implementation(libs.firebase.firestore)
  implementation(libs.osmdroid)
  debugImplementation(libs.androidx.compose.ui.tooling)
  testImplementation(libs.junit)
  testImplementation(libs.org.json) // real org.json for JVM tests (Android stubs it)
}
