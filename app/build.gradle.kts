import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Build-time PP-MattingV2 -> ncnn conversion plus Android Vulkan runtime/native bridge.
apply(from = "ppmatting-opencl.gradle")

fun sha256Of(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered().use { input ->
        val buffer = ByteArray(1024 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
}

fun downloadGeneratedAssetWithRetry(
    urls: List<String>,
    output: File,
    minimumBytes: Long,
    label: String,
    expectedSha256: String? = null,
) {
    if (output.isFile && output.length() > minimumBytes) {
        if (expectedSha256 == null || sha256Of(output).equals(expectedSha256, ignoreCase = true)) return
        output.delete()
    }
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
            expectedSha256?.let { expected ->
                val actual = sha256Of(temp)
                require(actual.equals(expected, ignoreCase = true)) {
                    "$label SHA-256 mismatch. Expected $expected but downloaded $actual"
                }
            }
            if (output.exists()) output.delete()
            check(temp.renameTo(output)) { "Could not install ${output.name}" }
            return
        } catch (error: Throwable) {
            lastError = error
            if (temp.exists()) temp.delete()
            if (attempt < attempts) {
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
                "https://storage.googleapis.com/mediapipe-models/image_segmenter/selfie_multiclass_256x256/float32/latest/hair_segmenter.tflite",
            ),
            output = output,
            minimumBytes = 200_000L,
            label = "SelfieMulticlass",
        )
    }
}

// V50 Pro Cutout uses PP-MattingV2/STDC1 512. The pinned ONNX model is the conversion source for
// the ncnn Vulkan GPU artifact and remains the lazy ONNX Runtime CPU reliability fallback.
val generatedPpMattingV2Assets = layout.buildDirectory.dir("generated/ppMattingV2Assets")
val ppMattingV2ModelFile = generatedPpMattingV2Assets.map { it.file("ppmattingv2_stdc1_human_512.onnx") }
val downloadPpMattingV2Model by tasks.registering {
    outputs.file(ppMattingV2ModelFile)
    doLast {
        val output = ppMattingV2ModelFile.get().asFile
        downloadGeneratedAssetWithRetry(
            urls = listOf(
                "https://huggingface.co/pstic/spatialthings-onnx/resolve/main/ppmattingv2-stdc1-human_512.onnx",
                "https://huggingface.co/pstic/spatialthings-onnx/resolve/main/ppmattingv2-stdc1-human_512.onnx?download=true",
            ),
            output = output,
            minimumBytes = 30_000_000L,
            label = "PP-MattingV2 STDC1 ONNX",
            expectedSha256 = "448d0a5d143426057e6cedbd1711ee8059b4f7057e030f0a33cab3a1ed141567",
        )
    }
}

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
    sourceSets["main"].assets.srcDir(generatedPpMattingV2Assets.get().asFile)
}

tasks.named("preBuild").configure {
    dependsOn(downloadHairSegmenterModel)
    dependsOn(downloadFaceSkinSegmenterModel)
    dependsOn(downloadPpMattingV2Model)
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

    // Lazy CPU reliability fallback. Primary PP-MattingV2 inference is ncnn Vulkan GPU.
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.29.0")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
}