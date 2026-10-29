plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "sh.haven.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "sh.haven.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 913
        versionName = "5.89.62"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    flavorDimensions += listOf("abi", "features")
    productFlavors {
        create("arm64") {
            dimension = "abi"
            ndk { abiFilters += "arm64-v8a" }
        }
        create("x64") {
            dimension = "abi"
            ndk { abiFilters += "x86_64" }
        }

        // One feature flavour. Task names keep the `Full` infix
        // (e.g. assembleX64FullDebug) so existing scripts and docs still
        // resolve.
        create("full") {
            dimension = "features"
            buildConfigField("boolean", "DESKTOP", "true")
        }
    }

    signingConfigs {
        create("release") {
            val ksFile = rootProject.file("haven-release.jks")
            if (ksFile.exists()) {
                storeFile = ksFile
                storePassword = System.getenv("KEYSTORE_PASSWORD") ?: ""
                keyAlias = System.getenv("KEY_ALIAS") ?: ""
                keyPassword = System.getenv("KEY_PASSWORD") ?: ""
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            // The release workflow passes -PnoLintVital: lintVitalAnalyze ran
            // concurrently with minifyWithR8 and that overlap is where the
            // 16G free runners got evicted (v5.89.0: 15.6G used, 373M
            // available, killed mid-R8). Nothing consumes lintVital's result
            // in CI — the gate lives in local release builds, which keep it.
            // (buildType-level checkReleaseBuilds is gone in AGP 9; the
            // lint {} block below is the replacement.)
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            vcsInfo.include = false
        }
        debug {
            // Sign debug with the release cert when the keystore env is present
            // (source ~/.haven-release.env), so a debuggable build installs over
            // a release-signed device build without a data-wiping uninstall.
            // Falls back to the default debug keystore when the env is absent.
            if (System.getenv("KEYSTORE_PASSWORD") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }


    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/versions/*/OSGI-INF/MANIFEST.MF"
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/NOTICE.md"
            excludes += "/META-INF/LICENSE.md"
            // Apache Mime4j (email MIME parsing) ships these in both its core and
            // dom jars; drop the duplicates so the APK merge doesn't conflict.
            excludes += "/META-INF/DEPENDENCIES"
            excludes += "/META-INF/{LICENSE,LICENSE.txt,NOTICE,NOTICE.txt}"
        }
        jniLibs {
            useLegacyPackaging = true
            // gomobile names every output libgojni.so. Only one producer is
            // left (rclone-android), but keep pickFirsts so a future variant
            // that adds another copy cannot fail the merge.
            pickFirsts += "**/libgojni.so"
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    lint {
        // -PnoLintVital (release workflow only): skip lintVital on release
        // builds. Its analyze/report ran concurrently with minifyWithR8 and
        // that overlap is where the 16G free runners got evicted (v5.89.0:
        // 15.6G used, 373M available, killed mid-R8); nothing in CI consumes
        // its result. Local release builds, which omit the property, keep
        // the gate.
        if (project.hasProperty("noLintVital")) {
            checkReleaseBuilds = false
        }
    }
}

// Version code scheme: base * 10 + abiOffset; APK named
// haven-<version>-<abi>-<buildtype>.apk. Rewritten from the removed
// applicationVariants API for AGP 9's Variant API; outputFileName still needs
// the impl cast — there is no public rename hook yet.
androidComponents {
    onVariants { variant ->
        val abiCodes = mapOf("arm64" to 1, "x64" to 2)
        val abi = variant.productFlavors.firstOrNull { it.first == "abi" }?.second
        val abiCode = abiCodes[abi]
        val base = android.defaultConfig.versionCode ?: 0
        val versionName = android.defaultConfig.versionName
        variant.outputs.forEach { output ->
            if (abiCode != null) {
                output.versionCode.set(base * 10 + abiCode)
            }
            (output as? com.android.build.api.variant.impl.VariantOutputImpl)
                ?.outputFileName?.set("haven-$versionName-$abi-${variant.buildType}.apk")
        }
    }
}

dependencies {
    implementation(project(":core:redact"))
    implementation(project(":core:ui"))
    implementation(project(":core:ssh"))
    implementation(project(":core:security"))
    implementation(project(":core:data"))

    implementation(project(":core:vnc"))
    implementation(project(":core:rdp"))
    implementation(project(":core:fido"))
    implementation(project(":core:terminal-haven"))
    // Direct termlib pull-in so the MCP agent transport can name termlib
    // public types (TerminalEmulator, ScrollController, SelectionController,
    // SelectionRange, AgentSnapshot) by class. core:terminal-haven uses
    // `implementation(libs.termlib)` so the dependency doesn't leak.
    implementation(libs.termlib)
    implementation(project(":core:tunnel"))

    implementation(project(":feature:connections"))
    implementation(project(":feature:terminal"))
    implementation(project(":feature:sftp"))
    implementation(project(":feature:keys"))
    implementation(project(":feature:settings"))
    implementation(project(":feature:vnc"))
    implementation(project(":feature:rdp"))
    // The app manifest declares CloudflareAccessLoginActivity, so app must
    // depend on its module directly — it only arrived transitively before,
    // which AGP 9 lint flags as MissingClass on the compile classpath.
    implementation(project(":feature:tunnel"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material3.window.size)
    implementation(libs.compose.material.icons)
    implementation(libs.compose.ui.tooling.preview)

    implementation(libs.core.ktx)
    implementation(libs.appcompat)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime)
    implementation(libs.lifecycle.process)
    implementation(libs.biometric)
    implementation(libs.navigation.compose)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)

    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    coreLibraryDesugaring(libs.desugar.jdk.libs)

    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.coroutines.test)
    testImplementation("org.json:json:20260814")
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test)
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

