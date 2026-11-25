plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
 namespace = "at.pogoplusgold.probe"
 compileSdk = 35
 defaultConfig { applicationId = "at.pogoplusgold.probe"; minSdk = 31; targetSdk = 35; versionCode = 1; versionName = "0.1-probe" }
 compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
 kotlinOptions { jvmTarget = "17" }
}
