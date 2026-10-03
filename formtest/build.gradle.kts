// Manual test bench for the autofill service: a native login form and a WebView form.
plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "ru.devkz.formtest"
    compileSdk = 35
    defaultConfig { applicationId = "ru.devkz.formtest"; minSdk = 30; targetSdk = 35; versionCode = 1; versionName = "1" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
