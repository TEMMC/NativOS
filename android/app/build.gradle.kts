import java.security.MessageDigest
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val releaseSigningPropertiesFile = rootProject.file("signing/keystore.properties")
val releaseSigningProperties = Properties().apply {
    if (releaseSigningPropertiesFile.isFile) {
        releaseSigningPropertiesFile.inputStream().use(::load)
    }
}

val preparePatchedXlorie by tasks.registering {
    val upstream = layout.projectDirectory.file(
        "src/main/xlorie-upstream/arm64-v8a/libXlorie.so"
    )
    val patched = layout.buildDirectory.file(
        "generated/mtOsJniLibs/arm64-v8a/libXlorie.so"
    )
    inputs.file(upstream)
    outputs.file(patched)

    doLast {
        val bytes = upstream.asFile.readBytes()
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(bytes).joinToString("") { "%02x".format(it) }
        check(digest == "f9d60f48c5af45971571783166437b25dfb84cf7a2233c0265c49f652a9fe851") {
            "libXlorie.so changed; review and update the cursor patch offsets"
        }

        fun patch(offset: Int, expected: ByteArray, replacement: ByteArray) {
            check(bytes.copyOfRange(offset, offset + expected.size).contentEquals(expected)) {
                "Unexpected libXlorie instruction at 0x${offset.toString(16)}"
            }
            replacement.copyInto(bytes, offset)
        }

        // Upstream ddxReady() forces CursorVisible=TRUE and installs rootCursor,
        // overriding Xorg's -nocursor flag. Preserve FALSE and skip that call.
        patch(0x0e3288, byteArrayOf(0x2b, 0x00, 0x80.toByte(), 0x52),
            byteArrayOf(0xeb.toByte(), 0x03, 0x1f, 0x2a))
        patch(0x0e32b8, byteArrayOf(0x80.toByte(), 0x01, 0x3f, 0xd6.toByte()),
            byteArrayOf(0x1f, 0x20, 0x03, 0xd5.toByte()))

        patched.get().asFile.apply {
            parentFile.mkdirs()
            writeBytes(bytes)
        }
    }
}

val mtOsRootfsUrl = providers.environmentVariable("MTOS_ROOTFS_URL").orElse("").get()
val mtOsRootfsSha256 = providers.environmentVariable("MTOS_ROOTFS_SHA256").orElse("").get()

val verifyRemoteRootfsMetadata by tasks.registering {
    doLast {
        check(mtOsRootfsUrl.startsWith("https://github.com/TEMMC/NativOS/releases/download/mt-os-rootfs-")) {
            "MTOS_ROOTFS_URL is missing or does not point to a versioned MT-OS rootfs release."
        }
        check(mtOsRootfsSha256.matches(Regex("[0-9a-fA-F]{64}"))) {
            "MTOS_ROOTFS_SHA256 must contain the SHA-256 digest of the published Debian rootfs."
        }
    }
}

android {
    namespace = "com.mt.os"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.mt.os"
        minSdk = 28  // Android 9+ (modern root tools, Camera2 stable)
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-alpha"
        buildConfigField("String", "MTOS_ROOTFS_URL", "\"$mtOsRootfsUrl\"")
        buildConfigField("String", "MTOS_ROOTFS_SHA256", "\"$mtOsRootfsSha256\"")

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    // Keep the original MT-OS package intact and publish MT-OS Desktop as a
    // separately installable edition. Both editions share the exact same
    // Phosh/Phoc runtime and native display bridge.
    flavorDimensions += "edition"
    productFlavors {
        create("standard") {
            dimension = "edition"
        }
        create("desktop") {
            dimension = "edition"
            applicationIdSuffix = ".desktop"
            versionNameSuffix = "-desktop"
        }
    }

    sourceSets {
        getByName("main") {
            java.srcDirs("src/main/java", "src/main/kotlin")
            jniLibs.srcDir(layout.buildDirectory.dir("generated/mtOsJniLibs"))
        }
    }

    signingConfigs {
        if (releaseSigningPropertiesFile.isFile) {
            create("release") {
                storeFile = rootProject.file(releaseSigningProperties.getProperty("storeFile"))
                storePassword = releaseSigningProperties.getProperty("storePassword")
                keyAlias = releaseSigningProperties.getProperty("keyAlias")
                keyPassword = releaseSigningProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (releaseSigningPropertiesFile.isFile) {
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

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
        aidl = true
    }
}

tasks.named("preBuild").configure {
    dependsOn(preparePatchedXlorie)
    dependsOn(verifyRemoteRootfsMetadata)
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.annotation:annotation:1.8.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.lifecycle:lifecycle-service:2.8.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // JSON serialization for bridge protocol
    implementation("org.json:json:20240303")
}
