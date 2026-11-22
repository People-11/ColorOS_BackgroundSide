import com.v7878.zygisk.gradle.ZygoteLoader

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.zygisk)
}

android {
    namespace = "io.github.people11.bgside"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "v0.1.0"
        // The Magisk zip takes its native libs (zygisk loader, PanamaPort) from the APK's lib/<abi>/.
        ndk { abiFilters += "arm64-v8a" }
    }

    buildTypes {
        release {
            // PanamaPort/AndroidVMTools are mostly unused here; their own R8 rules keep what they need.
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    // lintVital needs :vmtools' lint AAR, whose bundling requires the (disabled) AAR metadata check output.
    lint {
        checkReleaseBuilds = false
    }
}

zygisk {
    packages(ZygoteLoader.PACKAGE_SYSTEM_SERVER)

    id = "coloros_background_side"
    name = "ColorOS 小窗后台挂机"
    author = "People-11, Mubai-meng"
    description = "小窗最小化为边缘气泡后，应用移至保活虚拟屏继续运行（后台挂机）"
    entrypoint = "io.github.people11.bgside.Main"
    archiveName = "ColorOS_BackgroundSide"
    // ZygoteLoader unboxes this nullable Boolean without a null check; must be set explicitly.
    isAddVariantToArchiveName = false
}

dependencies {
    implementation(project(":vmtools"))
    implementation(libs.r8.annotations)
}
