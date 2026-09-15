plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val releaseSigningVariables = listOf(
    "DUO_RELEASE_STORE_FILE",
    "DUO_RELEASE_STORE_PASSWORD",
    "DUO_RELEASE_KEY_ALIAS",
    "DUO_RELEASE_KEY_PASSWORD",
)
val releaseSigningValues = releaseSigningVariables.associateWith { name ->
    System.getenv(name)?.takeIf { it.isNotBlank() }
}

val upstreamApplicationId = "com.jake.duolauncher"
val applicationIdOverride = System.getenv("DUO_APPLICATION_ID")?.takeIf { it.isNotBlank() }
val effectiveApplicationId = applicationIdOverride ?: upstreamApplicationId
val applicationIdPattern = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
check(applicationIdPattern.matches(effectiveApplicationId)) {
    "DUO_APPLICATION_ID must be a valid Android application ID with at least two dot-separated segments."
}

val configuredVersionCode = System.getenv("DUO_VERSION_CODE")?.takeIf { it.isNotBlank() }?.let { value ->
    value.toIntOrNull()?.takeIf { it in 1..2_100_000_000 }
        ?: error("DUO_VERSION_CODE must be an integer from 1 through 2100000000.")
}
val configuredVersionName = System.getenv("DUO_VERSION_NAME")?.takeIf { it.isNotBlank() }
val versionNamePattern = Regex("[0-9A-Za-z][0-9A-Za-z._-]{0,63}")
check(configuredVersionName == null || versionNamePattern.matches(configuredVersionName)) {
    "DUO_VERSION_NAME must use only letters, numbers, dots, underscores, or hyphens and be at most 64 characters."
}

val effectiveVersionCode = configuredVersionCode ?: 30
val effectiveVersionName = configuredVersionName ?: "0.15.0-beta01"
val suppliedReleaseSigningVariables = releaseSigningValues.filterValues { it != null }.keys
check(suppliedReleaseSigningVariables.isEmpty() || suppliedReleaseSigningVariables.size == releaseSigningVariables.size) {
    val missing = releaseSigningVariables.filterNot(suppliedReleaseSigningVariables::contains)
    "Release signing is only configured when all four DUO_RELEASE_* variables are set. Missing: ${missing.joinToString()}"
}

val releaseStoreFile = releaseSigningValues["DUO_RELEASE_STORE_FILE"]?.let { configuredPath ->
    rootProject.file(configuredPath).canonicalFile.also { storeFile ->
        val repositoryRoot = rootProject.projectDir.canonicalFile.toPath()
        check(!storeFile.toPath().startsWith(repositoryRoot)) {
            "DUO_RELEASE_STORE_FILE must point outside the repository."
        }
        check(storeFile.isFile && storeFile.canRead()) {
            "DUO_RELEASE_STORE_FILE does not point to a readable file."
        }
    }
}

android {
    namespace = upstreamApplicationId
    compileSdk = 36
    defaultConfig {
        applicationId = effectiveApplicationId
        minSdk = 31
        targetSdk = 36
        versionCode = effectiveVersionCode
        versionName = effectiveVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    signingConfigs {
        if (releaseStoreFile != null) {
            create("release") {
                storeFile = releaseStoreFile
                storePassword = releaseSigningValues.getValue("DUO_RELEASE_STORE_PASSWORD")
                keyAlias = releaseSigningValues.getValue("DUO_RELEASE_KEY_ALIAS")
                keyPassword = releaseSigningValues.getValue("DUO_RELEASE_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        getByName("debug") {
            applicationIdSuffix = ".debug"
        }
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (releaseStoreFile != null) signingConfig = signingConfigs.getByName("release")
        }
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

tasks.register("printReleaseMetadata") {
    group = "help"
    description = "Prints effective application and version metadata for release packaging."
    doLast {
        println("applicationId=$effectiveApplicationId")
        println("versionCode=$effectiveVersionCode")
        println("versionName=$effectiveVersionName")
    }
}
dependencies {
    implementation("androidx.window:window:1.5.1")
    implementation(platform("androidx.compose:compose-bom:2025.06.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.06.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
