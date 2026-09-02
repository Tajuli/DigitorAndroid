import java.io.File
import java.net.URI

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val generatedHairModelAssets = layout.buildDirectory.dir("generated/hairSegmenterAssets")
val hairSegmenterModelFile = generatedHairModelAssets.map { it.file("hair_segmenter.tflite") }
val downloadHairSegmenterModel by tasks.registering {
    outputs.file(hairSegmenterModelFile)
    doLast {
        val output = hairSegmenterModelFile.get().asFile
        if (output.isFile && output.length() > 1_000_000L) return@doLast
        output.parentFile.mkdirs()
        val temp = File(output.parentFile, output.name + ".download")
        if (temp.exists()) temp.delete()
        val url = URI(
            "https://storage.googleapis.com/mediapipe-models/image_segmenter/hair_segmenter/float32/latest/hair_segmenter.tflite",
        ).toURL()
        url.openStream().use { input ->
            temp.outputStream().buffered().use { target -> input.copyTo(target) }
        }
        require(temp.length() > 1_000_000L) { "Downloaded MediaPipe HairSegmenter model is unexpectedly small" }
        if (output.exists()) output.delete()
        check(temp.renameTo(output)) { "Could not install generated hair_segmenter.tflite asset" }
    }
}

android {
    namespace = "com.tajuli.digitorandroid"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.tajuli.digitorandroid"
        minSdk = 23
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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

    sourceSets["main"].assets.srcDir(generatedHairModelAssets)
}

tasks.named("preBuild").configure {
    dependsOn(downloadHairSegmenterModel)
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

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
}
