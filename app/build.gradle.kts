import com.android.build.api.dsl.ApplicationExtension

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.ksp)
}

configure<ApplicationExtension> {
    namespace = "io.github.mkl2008.pixelplaylyricon"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "io.github.mkl2008.pixelplaylyricon"
        minSdk = 30
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation(libs.lyricon.provider)
    implementation(libs.yukihookapi.api)
    implementation(libs.kavaref.core)
    implementation(libs.kavaref.extension)
    compileOnly(libs.xposed.api)
    ksp(libs.yukihookapi.ksp.xposed)
    implementation(libs.androidx.core.ktx)
}
