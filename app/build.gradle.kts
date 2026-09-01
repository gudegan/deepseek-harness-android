import java.security.MessageDigest
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 自用签名：从 keystore/keystore.properties 读取（已被 gitignore，不入库）
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore/keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.deepseek.dshshell"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.deepseek.dshshell"
        minSdk = 26
        targetSdk = 34
        versionCode = 39
        versionName = "0.5.24"
    }

    // 把 proot 作为原生库打进 APK：安装时由系统解压到 nativeLibraryDir，
    // 那里是 untrusted_app 唯一允许 execve 的目录（绕开 app_data_file 的 W^X / noexec 限制）
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    signingConfigs {
        create("release") {
            if (keystoreProps.isNotEmpty()) {
                storeFile = rootProject.file("keystore/${keystoreProps.getProperty("storeFile")}")
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (keystoreProps.isNotEmpty()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // commons-compress / xz 依赖少量 java.nio.file，需 core library desugaring
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

/**
 * 把 runtime-builder 的产物（runtime.tar.xz / runtime.version / proot）同步进 assets，
 * 并生成 runtime.sha256 供首启校验。产物缺失时跳过（源码树保持轻量）。
 */
val syncRuntimeAssets by tasks.registering {
    val dist = rootProject.file("runtime-builder/dist")
    val targetDir = layout.projectDirectory.dir("src/main/assets/runtime")
    inputs.dir(dist)
    outputs.dir(targetDir)
    doLast {
        val out = targetDir.asFile
        out.mkdirs()
        listOf("runtime.tar.xz", "runtime.version").forEach { name ->
            val src = File(dist, name)
            if (src.exists()) {
                src.copyTo(File(out, name), overwrite = true)
                logger.lifecycle("assets 同步: $name (${src.length()} bytes)")
            } else {
                logger.warn("assets 同步: 缺 $name，跳过")
            }
        }
        // 注意：proot 不再从 dist 同步。
        // 静态 glibc proot 在 untrusted_app seccomp 下会因 rseq 等系统调用被 TRAP 而 SIGSYS(159)，
        // 已改用 bionic 版 proot（jniLibs 内提交的 libproot.so + libproot-loader.so +
        // libtalloc.so + libandroid-shmem.so）。此任务若用 dist/proot 覆盖 jniLibs 会带回坏版本。
        val tarball = File(dist, "runtime.tar.xz")
        if (tarball.exists()) {
            val digest = MessageDigest.getInstance("SHA-256")
            tarball.inputStream().use { ins ->
                val buf = ByteArray(256 * 1024)
                while (true) {
                    val n = ins.read(buf)
                    if (n < 0) break
                    digest.update(buf, 0, n)
                }
            }
            File(out, "runtime.sha256").writeText(
                digest.digest().joinToString("") { "%02x".format(it) }
            )
        }
    }
}
tasks.named("preBuild") { dependsOn(syncRuntimeAssets) }

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.2")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.fragment:fragment-ktx:1.8.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.navigation:navigation-fragment-ktx:2.8.2")
    implementation("androidx.navigation:navigation-ui-ktx:2.8.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // runtime.tar.xz 解压（首启一次性）
    implementation("org.apache.commons:commons-compress:1.26.2")
    implementation("org.tukaani:xz:1.9")
}
