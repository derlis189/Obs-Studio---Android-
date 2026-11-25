plugins { id("com.android.application") }
val withOmt = providers.gradleProperty("withOmt").map(String::toBoolean).orElse(false)
val withSceneSwitcher = providers.gradleProperty("withSceneSwitcher").map(String::toBoolean).orElse(false)
val distributableAssets = tasks.register<Sync>("stageDistributableAssets") {
    from("src/main/assets")
    if (withOmt.get()) {
        from("../../out/omt-arm64/notices") { into("licenses/omt") }
    }
    if (withSceneSwitcher.get()) {
        from("../../out/android-scene-switcher/assets") {
            // OCR and speech extensions are excluded from the Android prototype.
            exclude("obs-modules/core/advanced-scene-switcher/res/ocr/**")
            exclude("obs-modules/core/advanced-scene-switcher/res/speech/**")
        }
    }
    exclude("vtuber/live2dcubismcore.min.js", "**/live2dcubismcore.min.js")
    into(layout.buildDirectory.dir("generated/distributableAssets"))
}

android {
    namespace = "org.obsandroid.port"
    compileSdk = 36
    ndkVersion = "29.0.14206865"
    defaultConfig {
        applicationId = "org.obsandroid.port"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.0.1-dev"
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild { cmake { arguments += "-DANDROID_STL=c++_shared" } }
    }
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.30.8" } }
    sourceSets.getByName("main").jniLibs.srcDir("../../out/android/jniLibs")
    if (withOmt.get()) {
        sourceSets.getByName("main").jniLibs.srcDir("../../out/android-omt/jniLibs")
    }
    if (withSceneSwitcher.get()) {
        sourceSets.getByName("main").jniLibs.srcDir("../../out/android-scene-switcher/jniLibs")
    }
    sourceSets.getByName("main").java.srcDir("../../tools/qt6/6.9.3/android_arm64_v8a/src/android/java/src")
    sourceSets.getByName("main").assets.setSrcDirs(
        listOf(layout.buildDirectory.dir("generated/distributableAssets"))
    )
    packaging {
        jniLibs {
            useLegacyPackaging = true
            excludes += "**/libndi.so"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
tasks.named("preBuild") { dependsOn(distributableAssets) }

dependencies {
    implementation("androidx.webkit:webkit:1.14.0")
    implementation(files(
        "../../tools/qt6/6.9.3/android_arm64_v8a/jar/Qt6Android.jar",
        "../../tools/qt6/6.9.3/android_arm64_v8a/jar/Qt6AndroidNetwork.jar"
    ))
    implementation("com.google.mlkit:segmentation-selfie:16.0.0-beta6")
}
