import java.io.File
import java.net.HttpURLConnection
import java.net.URI

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

fun downloadGeneratedAssetWithRetry(
    urls: List<String>,
    output: File,
    minimumBytes: Long,
    label: String,
) {
    if (output.isFile && output.length() > minimumBytes) return
    output.parentFile.mkdirs()
    val temp = File(output.parentFile, output.name + ".download")
    var lastError: Throwable? = null
    val attempts = 5

    for (attempt in 1..attempts) {
        if (temp.exists()) temp.delete()
        val sourceUrl = urls[(attempt - 1) % urls.size]
        var connection: HttpURLConnection? = null
        try {
            connection = URI(sourceUrl).toURL().openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = true
            connection.connectTimeout = 30_000
            connection.readTimeout = 180_000
            connection.setRequestProperty("User-Agent", "DigitorAndroid-build/1.0")
            connection.setRequestProperty("Accept", "application/octet-stream,*/*")
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                connection.errorStream?.use { it.readBytes() }
                error("$label download returned HTTP $responseCode")
            }
            connection.inputStream.buffered().use { input ->
                temp.outputStream().buffered().use { target -> input.copyTo(target, 1024 * 1024) }
            }
            require(temp.length() > minimumBytes) {
                "$label download is unexpectedly small (${temp.length()} bytes)"
            }
            if (output.exists()) output.delete()
            check(temp.renameTo(output)) { "Could not install ${output.name}" }
            return
        } catch (error: Throwable) {
            lastError = error
            if (temp.exists()) temp.delete()
            if (attempt < attempts) {
                // GitHub-hosted runners can briefly share a Hugging Face/IP rate limit. Back off
                // rather than turning a transient HTTP 429 into a false code failure.
                val delayMs = minOf(20_000L, 2_000L shl (attempt - 1))
                logger.warn("$label download attempt $attempt/$attempts failed: ${error.message}; retrying in ${delayMs}ms")
                Thread.sleep(delayMs)
            }
        } finally {
            connection?.disconnect()
        }
    }
    throw org.gradle.api.GradleException("Could not download $label after $attempts attempts", lastError)
}

val generatedHairModelAssets = layout.buildDirectory.dir("generated/hairSegmenterAssets")
val hairSegmenterModelFile = generatedHairModelAssets.map { it.file("hair_segmenter.tflite") }
val downloadHairSegmenterModel by tasks.registering {
    outputs.file(hairSegmenterModelFile)
    doLast {
        val output = hairSegmenterModelFile.get().asFile
        downloadGeneratedAssetWithRetry(
            urls = listOf(
                "https://storage.googleapis.com/mediapipe-models/image_segmenter/hair_segmenter/float32/latest/hair_segmenter.tflite",
            ),
            output = output,
            minimumBytes = 500_000L,
            label = "MediaPipe HairSegmenter",
        )
    }
}

val generatedFaceSkinModelAssets = layout.buildDirectory.dir("generated/faceSkinSegmenterAssets")
val faceSkinSegmenterModelFile = generatedFaceSkinModelAssets.map { it.file("selfie_multiclass_256x256.tflite") }
val downloadFaceSkinSegmenterModel by tasks.registering {
    outputs.file(faceSkinSegmenterModelFile)
    doLast {
        val output = faceSkinSegmenterModelFile.get().asFile
        downloadGeneratedAssetWithRetry(
            urls = listOf(
                "https://storage.googleapis.com/mediapipe-models/image_segmenter/selfie_multiclass_256x256/float32/latest/selfie_multiclass_256x256.tflite",
            ),
            output = output,
            minimumBytes = 200_000L,
            label = "SelfieMulticlass",
        )
    }
}

// V44+ Pro Cutout uses MODNet's soft portrait alpha instead of a binary person mask. MODNet and
// its published weights are Apache-2.0. This LiteRT conversion is GPU-friendly and keeps the model
// outside git history while still producing deterministic CI/phone builds.
val generatedPortraitMattingAssets = layout.buildDirectory.dir("generated/portraitMattingAssets")
val modNetModelFile = generatedPortraitMattingAssets.map { it.file("modnet_v44.tflite") }
val downloadModNetModel by tasks.registering {
    outputs.file(modNetModelFile)
    doLast {
        val output = modNetModelFile.get().asFile
        downloadGeneratedAssetWithRetry(
            urls = listOf(
                "https://huggingface.co/litert-community/MODNet-LiteRT/resolve/main/modnet.tflite",
                "https://huggingface.co/litert-community/MODNet-LiteRT/resolve/main/modnet.tflite?download=true",
            ),
            output = output,
            minimumBytes = 20_000_000L,
            label = "MODNet LiteRT",
        )
    }
}

// Optional CI/local packaging filter. Normal builds keep every ABI, while a phone APK can be built
// with `-PdigitorAbi=arm64-v8a` so MediaPipe/LiteRT native libraries are not duplicated for x86,
// x86_64 and armeabi-v7a. This changes packaging only; editor behavior is unchanged.
val requestedAbi = providers.gradleProperty("digitorAbi").orNull
    ?.trim()
    ?.takeIf { it.isNotEmpty() }
val supportedPackageAbis = setOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
require(requestedAbi == null || requestedAbi in supportedPackageAbis) {
    "Unsupported -PdigitorAbi=$requestedAbi. Expected one of ${supportedPackageAbis.joinToString()}."
}

android {
    namespace = "com.tajuli.digitorandroid"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.tajuli.digitorandroid"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        requestedAbi?.let { abi ->
            ndk {
                abiFilters += abi
            }
        }
    }

    buildTypes {
        create("phone") {
            initWith(getByName("debug"))
            isDebuggable = true
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("debug")
        }

        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    sourceSets["main"].assets.srcDir(generatedHairModelAssets.get().asFile)
    sourceSets["main"].assets.srcDir(generatedFaceSkinModelAssets.get().asFile)
    sourceSets["main"].assets.srcDir(generatedPortraitMattingAssets.get().asFile)
}

tasks.named("preBuild").configure {
    dependsOn(downloadHairSegmenterModel)
    dependsOn(downloadFaceSkinSegmenterModel)
    dependsOn(downloadModNetModel)
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.08.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    val media3 = "1.11.0"
    implementation("androidx.media3:media3-common:$media3")
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    implementation("androidx.media3:media3-transformer:$media3")
    implementation("androidx.media3:media3-effect:$media3")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.google.code.gson:gson:2.13.1")
    implementation("com.google.mlkit:face-detection:16.1.7")
    implementation("com.google.mediapipe:tasks-vision:0.10.35")

    // LiteRT 2.1.6+ currently has an AGP namespace collision between litert and its transitive
    // litert-api AAR. 2.1.5 predates that packaging regression and still exposes CompiledModel.
    implementation("com.google.ai.edge.litert:litert:2.1.5")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
}
