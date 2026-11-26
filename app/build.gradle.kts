plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

val resetQa = providers.gradleProperty("lanternResetQa").isPresent
if (resetQa) layout.buildDirectory.set(layout.projectDirectory.dir("build/reset-qa"))

android {
    namespace = "com.sider.lantern"
    compileSdk {
        version = release(37) { minorApiLevel = 1 }
    }
    defaultConfig {
        applicationId = "com.sider.lantern"
        minSdk = 29
        targetSdk = 36
        versionCode = 2
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        manifestPlaceholders["lanternLabel"] = "@string/app_name"
    }
    buildTypes {
        getByName("debug") {
            if (resetQa) {
                applicationIdSuffix = ".resetqa"
                manifestPlaceholders["lanternLabel"] = "Lantern Reset QA"
            }
        }
        getByName("release") {
            val storePath = providers.environmentVariable("LANTERN_KEYSTORE").orNull
            if (storePath != null) {
                signingConfig = signingConfigs.create("lanternRelease") {
                    storeFile = file(storePath)
                    storePassword = providers.environmentVariable("LANTERN_STORE_PASSWORD").get()
                    keyAlias = providers.environmentVariable("LANTERN_KEY_ALIAS").get()
                    keyPassword = providers.environmentVariable("LANTERN_KEY_PASSWORD").get()
                }
            }
        }
    }
    buildFeatures { compose = true }
    bundle { language { enableSplit = false } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

room { schemaDirectory("$projectDir/schemas") }

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)
    implementation(libs.text.vertical)
    implementation(libs.serialization.json)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    implementation(libs.datastore.preferences)
    implementation(libs.navigation.compose)
    ksp(libs.room.compiler)
    testImplementation(libs.junit)
    androidTestImplementation(libs.android.test.runner)
    androidTestImplementation(libs.android.test.junit)
}
