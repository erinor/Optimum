plugins {
  alias(libs.plugins.android.application)
}

android {
  namespace = "com.example"
  compileSdk = 36

  defaultConfig {
    applicationId = "com.example"
    minSdk = 31
    targetSdk = 36
    versionCode = 1
    versionName = "1.0"
  }

  buildTypes {
    release {
      isCrunchPngs = false
      isMinifyEnabled = false
      isShrinkResources = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
    }
    debug {
      signingConfig = null
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  packaging {
        resources {
            excludes += setOf(
                "META-INF/*.version",
                "/*.properties"
            )
        }
    }
}

dependencies {
  implementation(libs.androidx.appcompat)
  implementation(libs.material)
  implementation("io.github.reandroid:ARSCLib:1.3.8")
  implementation(libs.androidx.core.ktx)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)
}
