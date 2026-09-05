import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

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
                "https://storage.googleapis.com/mediapipe-models/image_segmenter/selfie_multiclass_256x256/float32/latest/selfie_multiclass_256x256.tflite",
            ),
            output = output,
            minimumBytes = 200_000L,
            label = "SelfieMulticlass",
        )
    }
}

// V56 keeps the exact PP-MattingV2/STDC1 512 network that produced CI804 quality, but converts the
// official Paddle inference model into two explicit Paddle Lite programs. The OpenCL program has no
// ARM kernels, so a successful predictor/run is guaranteed to be GPU-backed rather than a silent
// mixed CPU graph. The separate ARM program is the same network and is used only as the fallback.
// Large third-party artifacts remain build-generated instead of entering git history.
val paddleLiteVersionV56 = "v2.12"
val generatedPaddleLiteRuntimeV56 = layout.buildDirectory.dir("generated/paddleLiteRuntimeV56")
val paddleLitePredictorJarV56 = generatedPaddleLiteRuntimeV56.map { it.file("java/PaddlePredictor.jar") }
val paddleLiteJniRootV56 = generatedPaddleLiteRuntimeV56.map { it.dir("jniLibs") }
val paddleLiteArm64SoV56 = paddleLiteJniRootV56.map { it.file("arm64-v8a/libpaddle_lite_jni.so") }
val paddleLiteArmV7SoV56 = paddleLiteJniRootV56.map { it.file("armeabi-v7a/libpaddle_lite_jni.so") }

data class PaddleLiteRuntimeSpecV56(
    val abi: String,
    val archiveName: String,
    val archiveSha256: String,
    val destinationSo: File,
)

val preparePaddleLiteRuntimeV56 by tasks.registering {
    outputs.files(paddleLitePredictorJarV56, paddleLiteArm64SoV56, paddleLiteArmV7SoV56)
    doLast {
        val downloadDir = layout.buildDirectory.dir("downloads/paddleLiteV56").get().asFile
        val extractionRoot = layout.buildDirectory.dir("tmp/paddleLiteRuntimeV56").get().asFile
        val runtimeSpecs = listOf(
            PaddleLiteRuntimeSpecV56(
                "arm64-v8a",
                "inference_lite_lib.armv8.clang.with_exception.with_extra.with_cv.opencl.tar.gz",
                "c1d193033a0365e9542ac00b2a792b40c8749860a4266bda9b6f4608a22f4aff",
                paddleLiteArm64SoV56.get().asFile,
            ),
            PaddleLiteRuntimeSpecV56(
                "armeabi-v7a",
                "inference_lite_lib.armv7.clang.with_exception.with_extra.with_cv.opencl.tar.gz",
                "42bc61b81fb5b0a3336f774b4505246eb204c032a6a2970a82e389ae4057237a",
                paddleLiteArmV7SoV56.get().asFile,
            ),
        )
        var predictorJarSource: File? = null
        for ((abi, archiveName, archiveSha256, destinationSo) in runtimeSpecs) {
            val archive = File(downloadDir, archiveName)
            downloadGeneratedAssetWithRetry(
                urls = listOf(
                    "https://github.com/PaddlePaddle/Paddle-Lite/releases/download/" +
                        "$paddleLiteVersionV56/$archiveName",
                ),
                output = archive,
                minimumBytes = 3_000_000L,
                label = "Paddle Lite $paddleLiteVersionV56 OpenCL runtime ($abi)",
                expectedSha256 = archiveSha256,
            )
            logger.lifecycle("Paddle Lite $abi archive SHA-256: ${sha256Of(archive)}")

            val extracted = File(extractionRoot, abi)
            project.delete(extracted)
            project.copy {
                from(tarTree(resources.gzip(archive)))
                into(extracted)
            }
            val nativeLibrary = extracted.walkTopDown().firstOrNull {
                it.isFile && it.name == "libpaddle_lite_jni.so"
            } ?: error("Paddle Lite $abi OpenCL archive did not contain libpaddle_lite_jni.so")
            destinationSo.parentFile.mkdirs()
            nativeLibrary.copyTo(destinationSo, overwrite = true)

            if (predictorJarSource == null) {
                predictorJarSource = extracted.walkTopDown().firstOrNull {
                    it.isFile && it.name == "PaddlePredictor.jar"
                }
            }
        }
        val jarSource = predictorJarSource
            ?: error("Paddle Lite OpenCL runtime did not contain PaddlePredictor.jar")
        val jarOutput = paddleLitePredictorJarV56.get().asFile
        jarOutput.parentFile.mkdirs()
        jarSource.copyTo(jarOutput, overwrite = true)
    }
}

val generatedPpMattingV2Assets = layout.buildDirectory.dir("generated/ppMattingV2Assets")
val ppMattingV2OpenClModelV56 = generatedPpMattingV2Assets.map {
    it.file("ppmattingv2_stdc1_human_512_opencl.nb")
}
val ppMattingV2ArmModelV56 = generatedPpMattingV2Assets.map {
    it.file("ppmattingv2_stdc1_human_512_arm.nb")
}

val generatePpMattingV2PaddleLiteModelsV56 by tasks.registering {
    outputs.files(ppMattingV2OpenClModelV56, ppMattingV2ArmModelV56)
    doLast {
        val downloadDir = layout.buildDirectory.dir("downloads/paddleLiteV56").get().asFile
        val modelArchive = File(downloadDir, "ppmattingv2-stdc1-human_512.zip")
        val optBinary = File(downloadDir, "paddle_lite_opt_linux_$paddleLiteVersionV56")
        downloadGeneratedAssetWithRetry(
            urls = listOf(
                "https://paddleseg.bj.bcebos.com/matting/models/deploy/ppmattingv2-stdc1-human_512.zip",
            ),
            output = modelArchive,
            minimumBytes = 20_000_000L,
            label = "official PP-MattingV2 STDC1 512 Paddle inference model",
            expectedSha256 = "daff48b08c61958b9a21093791f6aed8eb3939b34b7418e40c18b2348136893d",
        )
        downloadGeneratedAssetWithRetry(
            urls = listOf(
                "https://github.com/PaddlePaddle/Paddle-Lite/releases/download/" +
                    "$paddleLiteVersionV56/opt_linux",
            ),
            output = optBinary,
            minimumBytes = 20_000_000L,
            label = "Paddle Lite $paddleLiteVersionV56 model optimizer",
            expectedSha256 = "22d559c5a6466996cbd78b33ff4f41e03b5e5aadecd787127c05ec8425f36262",
        )
        logger.lifecycle("PP-MattingV2 Paddle archive SHA-256: ${sha256Of(modelArchive)}")
        logger.lifecycle("Paddle Lite optimizer SHA-256: ${sha256Of(optBinary)}")
        check(optBinary.setExecutable(true) || optBinary.canExecute()) {
            "Could not make Paddle Lite optimizer executable"
        }

        val extracted = layout.buildDirectory.dir("tmp/ppMattingV2PaddleV56").get().asFile
        project.delete(extracted)
        project.copy {
            from(zipTree(modelArchive))
            into(extracted)
        }
        val modelFile = extracted.walkTopDown().firstOrNull {
            it.isFile && it.name == "model.pdmodel"
        } ?: error("PP-MattingV2 archive did not contain model.pdmodel")
        val paramsFile = File(modelFile.parentFile, "model.pdiparams")
        check(paramsFile.isFile) { "PP-MattingV2 archive did not contain model.pdiparams" }

        fun optimize(validTargets: String, output: File, enableFp16: Boolean) {
            val temporaryBase = File(output.parentFile, output.nameWithoutExtension + ".building")
            val temporaryModel = File(temporaryBase.absolutePath + ".nb")
            temporaryBase.delete()
            temporaryModel.delete()
            output.parentFile.mkdirs()
            val command = mutableListOf(
                optBinary.absolutePath,
                "--model_file=${modelFile.absolutePath}",
                "--param_file=${paramsFile.absolutePath}",
                "--valid_targets=$validTargets",
                "--optimize_out=${temporaryBase.absolutePath}",
                "--optimize_out_type=naive_buffer",
                "--enable_fp16=$enableFp16",
            )
            val process = ProcessBuilder(command)
                .redirectErrorStream(true)
                .start()
            val processOutput = process.inputStream.bufferedReader().use { it.readText() }
            val exitCode = process.waitFor()
            check(exitCode == 0) {
                "Paddle Lite optimizer failed for $validTargets (exit $exitCode):\n" +
                    processOutput.takeLast(16_000)
            }
            check(temporaryModel.isFile && temporaryModel.length() > 10_000_000L) {
                "Paddle Lite optimizer did not produce a valid $validTargets model"
            }
            temporaryModel.copyTo(output, overwrite = true)
            temporaryModel.delete()
            logger.lifecycle(
                "PP-MattingV2 $validTargets model: ${output.length()} bytes, " +
                    "SHA-256 ${sha256Of(output)}",
            )
        }

        // GPU and CPU stay as separate programs. The OpenCL model cannot silently execute ARM ops.
        optimize("opencl", ppMattingV2OpenClModelV56.get().asFile, enableFp16 = true)
        optimize("arm", ppMattingV2ArmModelV56.get().asFile, enableFp16 = false)
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
    sourceSets["main"].jniLibs.srcDir(paddleLiteJniRootV56.get().asFile)
}

tasks.named("preBuild").configure {
    dependsOn(downloadHairSegmenterModel)
    dependsOn(downloadFaceSkinSegmenterModel)
    dependsOn(preparePaddleLiteRuntimeV56)
    dependsOn(generatePpMattingV2PaddleLiteModelsV56)
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

    // PP-MattingV2 uses the official Paddle Lite v2.12 Java/OpenCL runtime generated above.
    implementation(files(paddleLitePredictorJarV56))

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
}
