import java.io.File
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

val maimaidBackendUrl = providers.gradleProperty("MAIMAID_BACKEND_URL")
    .orElse("https://api.rhythmeta.org")
val maimaidBackendAuthUrl = providers.gradleProperty("MAIMAID_BACKEND_AUTH_URL")
    .orElse("https://dash.rhythmeta.org")
val maimaidStaticAssetsUrl = providers.gradleProperty("MAIMAID_STATIC_ASSETS_URL")
    .orElse("https://maimaid-assets.rhythmeta.org")
val maimaidModelAssetsUrl = providers.gradleProperty("MAIMAID_MODEL_ASSETS_URL")
    .orElse("https://models.rhythmeta.org")
val maimaidUpdateUrl = providers.gradleProperty("MAIMAID_UPDATE_URL")
    .orElse("https://assets.rhythmeta.org/android/ci/main/latest.json")
// Single build-time env var holding every SGWCMAID deployment value as JSON, so endpoints,
// keys and salts are never baked into the sources. Leave it unset to build an APK without
// the SGWCMAID import (it then reports "sgwcmaid_not_configured").
//   MAIMAID_SGWCMAID='{"chimeUrl":"https://...","gameServer":"https://...","keychip":"A63E-...",
//     "aesKey":"...","aesIv":"...","apiSalt":"...","chimeSecret":"...","titleKey":"SDGB",
//     "placeId":1971,"regionId":21}'
val maimaidSgwcmaidConfig = providers.environmentVariable("MAIMAID_SGWCMAID").orElse("{}")
// Cabinet catalog for the SGWCMAID import. The catalog is deployment data and
// must never be committed. CI decodes MAIMAID_KEYCHIPS_GZIP to a file and
// exports MAIMAID_KEYCHIPS_FILE; local testers can point the same
// variable at a local JSON file or pass the JSON through MAIMAID_KEYCHIPS.
val maimaidKeychipsFile = providers.environmentVariable("MAIMAID_KEYCHIPS_FILE")
    .orElse(providers.gradleProperty("MAIMAID_KEYCHIPS_FILE"))
val maimaidKeychipsJson = providers.environmentVariable("MAIMAID_KEYCHIPS")
    .orElse(providers.gradleProperty("MAIMAID_KEYCHIPS"))
val emptySgwcmaidCatalog = """{"version":0,"source":"","provinces":[],"keychips":[]}"""
val sgwcmaidAssetsDir = layout.buildDirectory.dir("generated/sgwcmaid/assets").get().asFile
val sgwcmaidCatalogFile = sgwcmaidAssetsDir.resolve("keychips.json")
// Resolve inputs at configuration time so the task is configuration-cache safe.
val maimaidKeychipsPath = maimaidKeychipsFile.orNull
    ?.let { project.file(it).absolutePath }
val maimaidKeychipsInline = maimaidKeychipsJson.orNull
val prepareSgwcmaidAssets = tasks.register("prepareSgwcmaidAssets") {
    val keychipsPath = maimaidKeychipsPath
    val keychipsInline = maimaidKeychipsInline
    val fallbackCatalog = emptySgwcmaidCatalog
    val catalogFile = sgwcmaidCatalogFile
    inputs.property("keychipsFile", keychipsPath.orEmpty())
    inputs.property("keychipsJson", keychipsInline.orEmpty())
    keychipsPath?.let { inputs.file(it) }
    outputs.file(catalogFile)
    doLast {
        catalogFile.parentFile.mkdirs()
        val json = keychipsPath?.let { File(it).readText() } ?: keychipsInline
        catalogFile.writeText(json?.takeIf { it.isNotBlank() } ?: fallbackCatalog)
    }
}

fun gitBuildValue(vararg arguments: String) = providers.exec {
    workingDir(rootProject.projectDir.parentFile)
    commandLine("git", *arguments)
}.standardOutput.asText.map(String::trim)
val maimaidGitSha = providers.environmentVariable("GITHUB_SHA")
    .orElse(gitBuildValue("rev-parse", "HEAD"))
val maimaidGitBranch = providers.environmentVariable("GITHUB_REF_NAME")
    .orElse(gitBuildValue("rev-parse", "--abbrev-ref", "HEAD"))
val maimaidBuildTime = providers.environmentVariable("MAIMAID_BUILD_TIME")
    .orElse(gitBuildValue("show", "-s", "--format=%cI", "HEAD"))
fun buildConfigString(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
val maimaidBuildNumber = providers.environmentVariable("MAIMAID_BUILD_NUMBER")
    .orElse(
        providers.exec {
            commandLine(
                "sh",
                rootProject.file("../scripts/build-number.sh").absolutePath,
            )
        }.standardOutput.asText.map(String::trim),
    )
    .map { value ->
        value.toIntOrNull()?.takeIf { it > 0 }
            ?: error("Invalid MAIMAID_BUILD_NUMBER: $value")
    }
val splitReleaseApks = providers.gradleProperty("MAIMAID_SPLIT_RELEASE_APKS")
    .map { it.toBoolean() }
    .orElse(false)
val releaseKeystorePath = providers.environmentVariable("ANDROID_KEYSTORE_PATH")
val releaseKeystorePassword = providers.environmentVariable("ANDROID_KEYSTORE_PASSWORD")
val releaseKeyAlias = providers.environmentVariable("ANDROID_KEY_ALIAS")
val releaseKeyPassword = providers.environmentVariable("ANDROID_KEY_PASSWORD")
val releaseSigningConfigured = listOf(
    releaseKeystorePath,
    releaseKeystorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
).all { provider -> provider.orNull?.isNotBlank() == true }

android {
    namespace = "org.rhythmeta.maimaid"
    compileSdk = 37

    defaultConfig {
        applicationId = "org.rhythmeta.maimaid"
        minSdk = 29
        targetSdk = 37
        versionCode = maimaidBuildNumber.get()
		    versionName = "1.3.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "BACKEND_URL", "\"${maimaidBackendUrl.get()}\"")
        buildConfigField("String", "BACKEND_AUTH_URL", "\"${maimaidBackendAuthUrl.get()}\"")
        buildConfigField("String", "STATIC_ASSETS_URL", "\"${maimaidStaticAssetsUrl.get()}\"")
        buildConfigField("String", "MODEL_ASSETS_URL", "\"${maimaidModelAssetsUrl.get()}\"")
        buildConfigField("String", "UPDATE_URL", buildConfigString(maimaidUpdateUrl.get()))
        buildConfigField("String", "SGWCMAID_CONFIG", buildConfigString(maimaidSgwcmaidConfig.get()))
        buildConfigField("String", "GIT_SHA", buildConfigString(maimaidGitSha.get()))
        buildConfigField("String", "GIT_BRANCH", buildConfigString(maimaidGitBranch.get()))
        buildConfigField("String", "BUILD_TIME", buildConfigString(maimaidBuildTime.get()))
    }

    signingConfigs {
        getByName("debug") {
            enableV3Signing = true
        }
        if (releaseSigningConfigured) {
            create("release") {
                storeFile = file(releaseKeystorePath.get())
                storePassword = releaseKeystorePassword.get()
                keyAlias = releaseKeyAlias.get()
                keyPassword = releaseKeyPassword.get()
                storeType = "PKCS12"
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            if (releaseSigningConfigured) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    splits {
        abi {
            isEnable = splitReleaseApks.get()
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
            isUniversalApk = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    tasks.withType<KotlinJvmCompile>().configureEach {
        compilerOptions.jvmTarget.set(JvmTarget.JVM_21)
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
    }
    sourceSets {
        getByName("main").assets.directories.add(sgwcmaidAssetsDir.absolutePath)
    }
}

tasks.named("preBuild").configure { dependsOn(prepareSgwcmaidAssets) }

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.miuix.ui)
    implementation(libs.miuix.nav)
    implementation(libs.miuix.preference)
    implementation(libs.onnxruntime.android)
    implementation(libs.backdrop)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.mlkit.barcode)
    implementation(libs.androidx.browser)
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.protobuf.javalite)
    implementation(libs.material.kolor)
    implementation(libs.coil.compose)
    implementation(libs.androidx.palette)
    implementation(libs.okhttp)
    ksp(libs.androidx.room.compiler)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
