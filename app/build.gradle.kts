import java.io.File
import java.util.Locale
import java.util.Properties
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kover)
    id("com.google.dagger.hilt.android")
    id("com.google.devtools.ksp")
}

kotlin {
    jvmToolchain(17)
}

val hasGoogleServicesJson = project.file("google-services.json").isFile
if (hasGoogleServicesJson) {
    apply(plugin = "com.google.gms.google-services")
    apply(plugin = "com.google.firebase.crashlytics")
}

val localProperties = Properties().apply {
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.isFile) {
        localPropertiesFile.inputStream().use(::load)
    }
}

fun nonBlank(value: String?): String? = value?.trim()?.takeIf { it.isNotEmpty() }

fun resolveBuildSetting(vararg names: String): String =
    names.firstNotNullOfOrNull { name -> nonBlank(providers.gradleProperty(name).orNull) }
        ?: names.firstNotNullOfOrNull { name -> nonBlank(providers.environmentVariable(name).orNull) }
        ?: names.firstNotNullOfOrNull { name -> nonBlank(localProperties.getProperty(name)) }
        ?: ""

fun resolveReleaseSecretSetting(vararg names: String): String =
    names.firstNotNullOfOrNull { name -> nonBlank(providers.environmentVariable(name).orNull) }
        ?: ""

fun buildConfigString(value: String): String =
    "\"" + value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"") + "\""

val supabaseUrl = resolveBuildSetting("SUPABASE_URL")
val supabaseAnonKey = resolveBuildSetting("SUPABASE_ANON_KEY")
val geminiProxyUrl = resolveBuildSetting("GEMINI_PROXY_URL").ifBlank {
    if (supabaseUrl.isBlank()) "" else "${supabaseUrl.trimEnd('/')}/functions/v1/gemini-proxy"
}
val placesProxyUrl = resolveBuildSetting("PLACES_PROXY_URL").ifBlank {
    if (supabaseUrl.isBlank()) "" else "${supabaseUrl.trimEnd('/')}/functions/v1/google-places-proxy"
}
val androidTestEmail = resolveBuildSetting("WANDERLY_ANDROID_TEST_EMAIL")
val androidTestPassword = resolveBuildSetting("WANDERLY_ANDROID_TEST_PASSWORD")

val releaseStoreFilePath = resolveReleaseSecretSetting("RELEASE_STORE_FILE")
val releaseStorePassword = resolveReleaseSecretSetting("RELEASE_STORE_PASSWORD")
val releaseKeyAlias = resolveReleaseSecretSetting("RELEASE_KEY_ALIAS")
val releaseKeyPassword = resolveReleaseSecretSetting("RELEASE_KEY_PASSWORD")
val releaseStoreFile = nonBlank(releaseStoreFilePath)?.let { rawPath ->
    val rawFile = File(rawPath)
    if (rawFile.isAbsolute) rawFile else rootProject.file(rawPath)
}
val hasAnyReleaseSigningInput = listOf(
    releaseStoreFilePath,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword
).any { it.isNotBlank() }
val hasCompleteReleaseSigningInput = releaseStoreFile != null &&
    releaseStorePassword.isNotBlank() &&
    releaseKeyAlias.isNotBlank() &&
    releaseKeyPassword.isNotBlank()
val hasReleaseSigningConfig = hasCompleteReleaseSigningInput && releaseStoreFile?.isFile == true
val releaseSupabaseConfigUsesPlaceholders =
    supabaseUrl.contains("your-supabase-url", ignoreCase = true) ||
        supabaseAnonKey.contains("your-supabase-anon-key", ignoreCase = true)
val canAssembleReleaseForSizeReport =
    supabaseUrl.isNotBlank() &&
        supabaseUrl.startsWith("https://") &&
        !releaseSupabaseConfigUsesPlaceholders

abstract class ValidateReleaseConfigTask : DefaultTask() {
    @get:Input
    abstract val hasSupabaseUrl: Property<Boolean>

    @get:Input
    abstract val hasSupabaseAnonKey: Property<Boolean>

    @get:Input
    abstract val supabaseUrlUsesHttps: Property<Boolean>

    @get:Input
    abstract val usesPlaceholderValues: Property<Boolean>

    @TaskAction
    fun validate() {
        if (!hasSupabaseUrl.get() || !hasSupabaseAnonKey.get()) {
            throw GradleException(
                "Release Supabase config is missing. Provide SUPABASE_URL and SUPABASE_ANON_KEY via " +
                    "GitHub secrets/environment variables, Gradle properties (-P...), or local.properties."
            )
        }
        if (!supabaseUrlUsesHttps.get()) {
            throw GradleException("Release SUPABASE_URL must be a non-placeholder HTTPS URL.")
        }
        if (usesPlaceholderValues.get()) {
            throw GradleException("Release Supabase config still contains template placeholder values.")
        }
    }
}

abstract class ValidateReleaseSigningTask : DefaultTask() {
    @get:Input
    abstract val hasAnySigningInput: Property<Boolean>

    @get:Input
    abstract val hasUsableSigningConfig: Property<Boolean>

    @TaskAction
    fun validate() {
        if (hasAnySigningInput.get() && !hasUsableSigningConfig.get()) {
            throw GradleException(
                "Incomplete release signing config. Provide RELEASE_STORE_FILE, RELEASE_STORE_PASSWORD, " +
                    "RELEASE_KEY_ALIAS, and RELEASE_KEY_PASSWORD; ensure RELEASE_STORE_FILE points to an existing keystore."
            )
        }
    }
}

abstract class ReleaseRegressionCheckTask : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceFiles: ConfigurableFileCollection

    @get:Input
    abstract val projectDirPath: Property<String>

    @TaskAction
    fun check() {
        val projectDir = File(projectDirPath.get())
        val forbiddenTerms = listOf(
            "android.permission.SCHEDULE_EXACT_ALARM",
            "setExact(",
            "setExactAndAllowWhileIdle(",
            "canScheduleExactAlarms(",
            "REFRESH_INTERVAL_MILLIS = 15_000L",
            "15_000L // widget"
        )
        val violations = mutableListOf<String>()
        sourceFiles.files
            .filter { it.isFile }
            .forEach { file ->
                val text = file.readText()
                forbiddenTerms.forEach { term ->
                    if (text.contains(term)) {
                        violations += "${file.relativeTo(projectDir)} contains forbidden release regression marker: $term"
                    }
                }
            }

        if (violations.isNotEmpty()) {
            throw GradleException(
                "Release regression check failed:\n" + violations.joinToString(separator = "\n")
            )
        }
    }
}

abstract class ApkSizeReportTask : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val apkRoots: ConfigurableFileCollection

    @get:Input
    abstract val projectDirPath: Property<String>

    @get:OutputFile
    abstract val reportFile: RegularFileProperty

    @TaskAction
    fun report() {
        val projectDir = File(projectDirPath.get())
        val apkFiles = apkRoots.files
            .flatMap { root ->
                when {
                    root.isDirectory -> root.walkTopDown().filter { it.isFile && it.extension == "apk" }.toList()
                    root.isFile && root.extension == "apk" -> listOf(root)
                    else -> emptyList()
                }
            }
            .distinct()
            .sortedBy { it.path }

        val lines = if (apkFiles.isEmpty()) {
            listOf("No APK artifacts found under app/build/outputs/apk.")
        } else {
            apkFiles.map { file ->
                val mib = file.length().toDouble() / (1024.0 * 1024.0)
                "${file.relativeTo(projectDir)}: ${"%.2f".format(Locale.US, mib)} MiB (${file.length()} bytes)"
            }
        }

        val outputFile = reportFile.get().asFile
        outputFile.parentFile.mkdirs()
        outputFile.writeText(lines.joinToString(separator = System.lineSeparator()) + System.lineSeparator())
        lines.forEach { logger.lifecycle(it) }
    }
}

android {
    namespace = "com.novahorizon.wanderly"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.novahorizon.wanderly"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        testInstrumentationRunnerArguments["wanderly.test.email"] = androidTestEmail
        testInstrumentationRunnerArguments["wanderly.test.password"] = androidTestPassword
        manifestPlaceholders["crashlyticsCollectionEnabled"] = "false"
    }

    signingConfigs {
        if (hasReleaseSigningConfig) {
            create("release") {
                storeFile = releaseStoreFile
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            buildConfigField("String", "SUPABASE_URL", buildConfigString(supabaseUrl))
            buildConfigField("String", "SUPABASE_ANON_KEY", buildConfigString(supabaseAnonKey))
            buildConfigField("String", "GEMINI_PROXY_URL", buildConfigString(geminiProxyUrl))
            buildConfigField("String", "PLACES_PROXY_URL", buildConfigString(placesProxyUrl))
            buildConfigField("Boolean", "CRASH_REPORTING_CONFIGURED", hasGoogleServicesJson.toString())
            manifestPlaceholders["crashlyticsCollectionEnabled"] = "false"
        }

        release {
            isMinifyEnabled = true
            isShrinkResources = true
            buildConfigField("String", "SUPABASE_URL", buildConfigString(supabaseUrl))
            buildConfigField("String", "SUPABASE_ANON_KEY", buildConfigString(supabaseAnonKey))
            buildConfigField("String", "GEMINI_PROXY_URL", buildConfigString(geminiProxyUrl))
            buildConfigField("String", "PLACES_PROXY_URL", buildConfigString(placesProxyUrl))
            buildConfigField("Boolean", "CRASH_REPORTING_CONFIGURED", hasGoogleServicesJson.toString())
            manifestPlaceholders["crashlyticsCollectionEnabled"] = hasGoogleServicesJson.toString()
            if (hasReleaseSigningConfig) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

kover {
    reports {
        variant("debug") {
            verify {
                rule("Minimum debug line coverage") {
                    minBound(15)
                }
            }
        }
    }
}

val validateReleaseConfig by tasks.registering(ValidateReleaseConfigTask::class) {
    group = "verification"
    description = "Fails release packaging when required Supabase config is absent or unsafe."
    hasSupabaseUrl.set(supabaseUrl.isNotBlank())
    hasSupabaseAnonKey.set(supabaseAnonKey.isNotBlank())
    supabaseUrlUsesHttps.set(supabaseUrl.startsWith("https://"))
    usesPlaceholderValues.set(releaseSupabaseConfigUsesPlaceholders)
}

val validateReleaseSigning by tasks.registering(ValidateReleaseSigningTask::class) {
    group = "verification"
    description = "Fails only when partial release signing inputs are supplied."
    hasAnySigningInput.set(hasAnyReleaseSigningInput)
    hasUsableSigningConfig.set(hasReleaseSigningConfig)
}

val releaseRegressionCheck by tasks.registering(ReleaseRegressionCheckTask::class) {
    group = "verification"
    description = "Blocks regressions for exact alarms and aggressive widget refresh markers."
    projectDirPath.set(project.projectDir.absolutePath)
    sourceFiles.from(
        fileTree("src/main") {
            include("**/*.kt", "**/*.xml")
        }
    )
}

val apkSizeReport by tasks.registering(ApkSizeReportTask::class) {
    group = "verification"
    description = "Prints and writes APK artifact sizes for CI visibility."
    dependsOn("assembleDebug")
    if (canAssembleReleaseForSizeReport) {
        dependsOn("assembleRelease")
    }
    projectDirPath.set(project.projectDir.absolutePath)
    apkRoots.from(layout.buildDirectory.dir("outputs/apk"))
    reportFile.set(layout.buildDirectory.file("reports/apk-size/apk-size-summary.txt"))
}

tasks.configureEach {
    if (name == "preReleaseBuild") {
        dependsOn(validateReleaseConfig, validateReleaseSigning)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.appcompat)
    implementation(libs.com.google.android.material.material)
    implementation(libs.androidx.constraintlayout.constraintlayout)
    implementation(libs.androidx.viewpager2)
    
    // Navigation Component
    implementation(libs.androidx.navigation.fragment.ktx)
    implementation(libs.androidx.navigation.ui.ktx)
    
    // OSMDroid
    implementation(libs.osmdroid.android)
    
    // Location
    implementation(libs.play.services.location)
    
    // Supabase
    implementation(platform(libs.supabase.bom))
    implementation(libs.supabase.postgrest)
    implementation(libs.supabase.gotrue)
    implementation(libs.supabase.realtime)
    implementation(libs.ktor.client.okhttp)
    
    // KotlinX Serialization
    implementation(libs.kotlinx.serialization.json)
    
    // Coroutines
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.datastore.preferences)

    // Hilt
    implementation("com.google.dagger:hilt-android:2.59.1")
    ksp("com.google.dagger:hilt-android-compiler:2.59.1")
    implementation("androidx.hilt:hilt-navigation-fragment:1.2.0")

    // Glide
    implementation(libs.glide)
    
    // UCrop for image cropping
    implementation(libs.ucrop)

    // OkHttp
    implementation(libs.okhttp)
    
    // WorkManager
    implementation(libs.androidx.work.runtime.ktx)

    // Crash reporting. Runtime collection remains disabled unless google-services.json is present.
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.crashlytics)
    implementation(libs.firebase.analytics)

    // Baseline Profile installation for release builds and sideloaded internal candidates.
    implementation(libs.androidx.profileinstaller)

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.arch.core.testing)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation("com.google.dagger:hilt-android-testing:2.59.1")
    kspTest("com.google.dagger:hilt-android-compiler:2.59.1")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.espresso.intents)
    androidTestImplementation("com.google.dagger:hilt-android-testing:2.59.1")
    kspAndroidTest("com.google.dagger:hilt-android-compiler:2.59.1")
}
