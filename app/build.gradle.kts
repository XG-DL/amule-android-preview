plugins {
    id("com.android.application")
}

android {
    namespace = "uk.xgdl.amuleprobe"
    compileSdk = 36
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "uk.xgdl.amuleprobe"
        minSdk = 33
        targetSdk = 36
        versionCode = 4
        versionName = "0.1.3"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    if (providers.gradleProperty("androidExporterTestHost").orNull == "true") {
        sourceSets.getByName("main").jniLibs.setSrcDirs(emptyList<String>())
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    androidTestImplementation("androidx.test:core:1.7.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
}
