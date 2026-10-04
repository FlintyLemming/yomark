import com.android.build.api.artifact.SingleArtifact

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    id("org.jetbrains.kotlin.plugin.parcelize")
}


abstract class AssertPermissionsTask : DefaultTask() {

    @get:InputFile
    abstract val mergedManifest: RegularFileProperty

    @TaskAction
    fun check() {
        val text = mergedManifest.get().asFile.readText()
        val found = Regex("""<uses-permission[^>]*android:name="([^"]+)"""")
            .findAll(text)
            .map { it.groupValues[1] }
            .toSortedSet()
        val offenders = found - ALLOWED
        if (offenders.isNotEmpty()) {
            throw GradleException(
                "\u5408\u5e76\u540e manifest \u51fa\u73b0\u4e86\u4e0d\u5141\u8bb8\u7684\u6743\u9650\uff1a${offenders.joinToString()}\n" +
                    "\u672c\u9879\u76ee\u7684\u6838\u5fc3\u627f\u8bfa\u662f\u8fd0\u884c\u671f\u654f\u611f\u6743\u9650\u4e3a 0\uff08\u89c1 spec \u00a70 / \u00a713\uff09\u3002" +
                    "\u552f\u4e00\u5141\u8bb8\u7684\u662f ${ALLOWED.joinToString()}\u3002"
            )
        }
        logger.lifecycle("\u6743\u9650\u65ad\u8a00\u901a\u8fc7\uff1a\u5408\u5e76\u540e manifest \u7684 uses-permission = ${found.joinToString().ifEmpty { "\uff08\u7a7a\uff09" }}")
    }

    companion object {
        /**
         * \u5141\u8bb8\u540d\u5355\u53ea\u80fd\u88c5\u300c\u4e0d\u6388\u4e88\u4efb\u4f55\u6570\u636e\u8bbf\u95ee\u80fd\u529b\u300d\u7684\u6761\u76ee\uff0c\u9010\u6761\u5199\u6b7b\uff0c\u4e0d\u5141\u8bb8\u524d\u7f00\u5339\u914d\u3002
         * \u65b0\u51fa\u73b0\u7684\u4efb\u4f55\u6743\u9650\u90fd\u5e94\u8be5\u8ba9\u6784\u5efa\u5931\u8d25\uff0c\u7531\u4eba\u5224\u65ad\u540e\u518d\u51b3\u5b9a\u8981\u4e0d\u8981\u52a0\u8fdb\u6765\u3002
         *
         * - com.android.vending.BILLING\uff1aPlay Billing \u5408\u5e76\u8fdb\u6765\uff0c\u4e0d\u53ef\u89c4\u907f\u3002
         * - com.google.android.apps.aicore.service.BIND_SERVICE\uff1aML Kit GenAI\uff08Gemini Nano\uff09\u5408\u5e76\u8fdb\u6765\u3002
         *   \u53ea\u7528\u4e8e\u7ed1\u5b9a\u7cfb\u7edf\u7684 AICore \u670d\u52a1\u3001\u7531\u5b83\u5728\u672c\u673a\u8dd1\u6a21\u578b\uff1b
         *   \u4e0d\u5f39\u6388\u6743\u6846\u3001\u4e0d\u89e6\u53ca\u4efb\u4f55\u7528\u6237\u6570\u636e\u3001\u672c\u5e94\u7528\u4e5f\u4e0d\u56e0\u6b64\u8054\u7f51\u3002
         * - com.youma.app.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION\uff1aandroidx.core \u5408\u5e76\u8fdb\u6765\u3002
         *   \u5b83\u662f\u672c\u5e94\u7528\u7ed9\u81ea\u5df1\u5b9a\u4e49\u7684 protectionLevel="signature" \u6743\u9650\uff0c
         *   \u7528\u4e8e ContextCompat.registerReceiver \u6ce8\u518c\u975e\u5bfc\u51fa\u5e7f\u64ad\u63a5\u6536\u5668\u3002
         *   \u5b83\u4e0d\u662f\u8fd0\u884c\u671f\u6743\u9650\uff1a\u4e0d\u5f39\u6388\u6743\u6846\u3001\u4e0d\u89e6\u53ca\u4efb\u4f55\u7528\u6237\u6570\u636e\uff0c
         *   \u4e14\u56e0\u4e3a\u662f signature \u7ea7\uff0c\u5176\u4ed6\u5e94\u7528\u6839\u672c\u62ff\u4e0d\u5230\u3002
         */
        val ALLOWED = setOf(
            "com.android.vending.BILLING",
            "com.google.android.apps.aicore.service.BIND_SERVICE",
            "com.youma.app.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
        )
    }
}

android {
    namespace = "com.youma.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.youma.app"
        minSdk = 29                 // Android 10：作用域存储，读写自己创建的媒体文件免权限
        targetSdk = 36
        versionCode = 6
        versionName = "0.3.2"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    kotlin { jvmToolchain(21) }
    buildFeatures { compose = true }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    // 直接装的 APK 也按 ABI 拆：ONNX Runtime 的 .so 每个 ABI 二三十 MB，四个塞进一个包就是三百多 MB。
    // 真机装 arm64-v8a 那个，模拟器装 x86_64 那个。
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
            isUniversalApk = false
        }
    }

    // AAB 同样按 ABI 分发。原 spec §13 的 25 MB 包体上限已取消（换 PP-OCR 时的决定）。
    bundle {
        abi { enableSplit = true }
        density { enableSplit = true }
        language { enableSplit = true }
    }
}


androidComponents {
    onVariants { variant ->
        val name = variant.name.replaceFirstChar { it.uppercase() }
        val task = tasks.register<AssertPermissionsTask>("assert${name}NoRuntimePermissions") {
            group = "verification"
            description = "\u65ad\u8a00\u5408\u5e76\u540e manifest \u9664 BILLING \u5916\u6ca1\u6709\u4efb\u4f55 uses-permission"
            mergedManifest.set(variant.artifacts.get(SingleArtifact.MERGED_MANIFEST))
        }
        tasks.named("check").configure { dependsOn(task) }
    }
}

configurations.matching { it.name.endsWith("UnitTestRuntimeClasspath") }.configureEach {
    exclude(group = "com.microsoft.onnxruntime", module = "onnxruntime-android")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.savedstate)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.exifinterface)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.mlkit.text.recognition)
    implementation(libs.mlkit.text.recognition.chinese)
    implementation(libs.mlkit.face.detection)
    implementation(libs.mlkit.barcode.scanning)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.libphonenumber)
    // 人名的字面识别（PersonNameRecognizer）。portable 版把预编译好的词典打在 jar 里，约 8 MB，不联网
    implementation(libs.hanlp)
    implementation(libs.onnxruntime.android)
    implementation(libs.mlkit.genai.prompt)
    implementation(libs.billing.ktx)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.test.core)
    // PP-OCR 的单测在 JVM 上跑真的 ONNX 模型：换成桌面版 onnxruntime（API 与 Android 版同一套 ai.onnxruntime），
    // Android 版的 .so 在 JVM 上加载不了，从单测运行期 classpath 里摘掉。
    testImplementation(libs.onnxruntime.jvm)
    // 设置页的系统栏避让在 Robolectric 上测：喂一组 insets，量标题栏与最后一行落在哪
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    // Compose ui-test 传递进来的 espresso-core 3.5.0 在 API 35+ 上崩：
    // 它反射 android.hardware.input.InputManager.getInstance，那个方法已被移除。
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.truth)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}
