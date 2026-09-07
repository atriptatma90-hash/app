plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
val keyPath=System.getenv("ANDROID_KEYSTORE_PATH")
android {
 namespace="app.downloadverse.android"
 compileSdk=35
 buildToolsVersion="35.0.0"
 defaultConfig {
  applicationId="app.downloadverse.android"
  minSdk=29
  targetSdk=35
  versionCode=1
  versionName="1.0.0-preview"
  testInstrumentationRunner="androidx.test.runner.AndroidJUnitRunner"
  ndk { abiFilters += listOf("arm64-v8a","armeabi-v7a","x86_64") }
 }
 signingConfigs {
  if(!keyPath.isNullOrBlank())create("privateRelease") {
   storeFile=file(requireNotNull(keyPath))
   storePassword=System.getenv("ANDROID_STORE_PASSWORD")
   keyAlias=System.getenv("ANDROID_KEY_ALIAS")
   keyPassword=System.getenv("ANDROID_KEY_PASSWORD")
   enableV1Signing=true;enableV2Signing=true;enableV3Signing=true
  }
 }
 buildTypes {
  debug { applicationIdSuffix=".debug";versionNameSuffix="-debug" }
  release { isDebuggable=false;isMinifyEnabled=false;if(!keyPath.isNullOrBlank())signingConfig=signingConfigs.getByName("privateRelease") }
 }
 compileOptions { sourceCompatibility=JavaVersion.VERSION_17;targetCompatibility=JavaVersion.VERSION_17 }
 kotlinOptions { jvmTarget="17" }
 buildFeatures { buildConfig=true }
 packaging { jniLibs { useLegacyPackaging=true } }
 lint { abortOnError=true;checkReleaseBuilds=true }
 testOptions { animationsDisabled=true }
}
dependencies {
 implementation("androidx.core:core-ktx:1.15.0")
 implementation("androidx.activity:activity-ktx:1.10.1")
 implementation("io.github.junkfood02.youtubedl-android:library:0.18.1")
 implementation("io.github.junkfood02.youtubedl-android:ffmpeg:0.18.1")
 testImplementation("junit:junit:4.13.2")
 androidTestImplementation("androidx.test:runner:1.6.2")
 androidTestImplementation("androidx.test:core-ktx:1.6.1")
 androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
