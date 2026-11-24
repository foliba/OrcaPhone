import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("app.cash.licensee")
}

android {
    namespace = "io.github.foliba.phorca"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.foliba.phorca"
        minSdk = 26
        // Google Play requires a recent target: API 36 (Android 16) for new apps and updates since Aug 2026
        targetSdk = 36
        versionCode = 18
        versionName = "0.5.5"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Release signing: the upload key comes from the environment (GitHub secrets in release.yml).
    // Without it, release builds are signed with the debug key so CI artifacts still install.
    val uploadKey = System.getenv("PHORCA_KEYSTORE")?.let { file(it) }?.takeIf { it.exists() }
    signingConfigs {
        if (uploadKey != null) create("upload") {
            storeFile = uploadKey
            storePassword = System.getenv("PHORCA_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("PHORCA_KEY_ALIAS")
            keyPassword = System.getenv("PHORCA_KEY_PASSWORD") ?: System.getenv("PHORCA_KEYSTORE_PASSWORD")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName(if (uploadKey != null) "upload" else "debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources.excludes += setOf("META-INF/{AL2.0,LGPL2.1}", "META-INF/versions/9/previous-compilation-data.bin")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":slicer-core"))

    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.webkit:webkit:1.12.1")
    // draws the OrcaSlicer bed textures (SVG)
    implementation("com.caverock:androidsvg-aar:1.4")

    // Device-test libraries: CI resolves them in the build job, so the emulator jobs find them in the Gradle cache.
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test:core:1.7.0")
    // 3.7.0+: older Espresso calls InputManager.getInstance(), which Android 17 removed (every UI test fails);
    // declared explicitly so it wins over the older one Compose's ui-test pulls in
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}

// Every library in the APK must have an approved licence, otherwise the build fails. The resulting
// list (with versions and licences) is bundled as assets/licenses/libraries.json and shown on the
// About & licences screen.
licensee {
    allow("Apache-2.0")
    allow("MIT")
    allow("BSD-3-Clause")
    allowDependency("org.locationtech.jts", "jts-core", "1.20.0") {
        because("dual-licensed EPL-2.0 / EDL-1.0 (BSD-3-Clause); Phorca uses it under EDL-1.0")
    }
}

abstract class BundleLicenseReport : DefaultTask() {
    @get:InputFile
    abstract val report: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() {
        val dir = outputDir.get().asFile.resolve("licenses").apply { mkdirs() }
        report.get().asFile.copyTo(dir.resolve("libraries.json"), overwrite = true)
    }
}

androidComponents {
    onVariants { variant ->
        val name = variant.name.replaceFirstChar { it.uppercase() }
        val bundle = tasks.register<BundleLicenseReport>("bundleLicenseReport$name") {
            report.set(tasks.named<app.cash.licensee.LicenseeTask>("licenseeAndroid$name").flatMap { it.jsonOutput })
        }
        variant.sources.assets?.addGeneratedSourceDirectory(bundle, BundleLicenseReport::outputDir)
    }
}

// CI: the build job downloads the device-test runner's own libraries (AGP's unified test platform), so the
// emulator jobs find them in the Gradle cache instead of all asking Maven Central at once (which answers 429).
tasks.register("resolveDeviceTestRunner") {
    notCompatibleWithConfigurationCache("resolves AGP's test platform configurations by name")
    doLast {
        val utp = project.configurations.filter { it.name.startsWith("unified-test-platform") && it.isCanBeResolved }
        utp.forEach { it.resolve() }
        println("Resolved ${utp.size} test platform configurations: ${utp.joinToString { it.name }}")
    }
}
