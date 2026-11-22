plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.google.services)
}

kotlin {
    androidTarget {
        compilations.all {
            compileTaskProvider.configure {
                compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21) }
            }
        }
    }
    sourceSets {
        androidMain.dependencies {
            implementation(projects.composeApp)
            implementation(projects.core.data)
            implementation(projects.core.domain)
            implementation(libs.androidx.activity.compose)
            implementation(libs.androidx.exifinterface)
            implementation(libs.koin.android)
            implementation(libs.androidx.work.runtime.ktx)
            implementation(libs.kotlinx.serialization.json)
            implementation(project.dependencies.platform(libs.firebase.bom))
            implementation(libs.firebase.analytics)
        }
    }
}

android {
    namespace = "id.tetuko.app"
    compileSdk = 35
    defaultConfig {
        applicationId = "id.tetuko.app"
        minSdk = 26
        targetSdk = 35
        versionCode = providers.gradleProperty("app.versionCode").getOrElse("1").toInt()
        versionName = providers.gradleProperty("app.versionName").getOrElse("1.0.0")
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_21; targetCompatibility = JavaVersion.VERSION_21 }
    buildFeatures { buildConfig = true }
    val firebaseApiKey = providers.environmentVariable("FIREBASE_API_KEY").orElse(providers.gradleProperty("firebaseApiKey").orElse(""))
    val firebaseProjectId = providers.environmentVariable("FIREBASE_PROJECT_ID").orElse(providers.gradleProperty("firebaseProjectId").orElse(""))
    defaultConfig {
        buildConfigField("String", "FIREBASE_API_KEY", "\"${firebaseApiKey.get()}\"")
        buildConfigField("String", "FIREBASE_PROJECT_ID", "\"${firebaseProjectId.get()}\"")
    }
    packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
}
