// 独立的小工程，不进 app 的构建：直接编译 app 里的 PP-OCR 与提示词源码（原文件，不拷贝），
// 在桌面 JVM 上跑随包的同一套模型，给 model-eval 的脚本出 OCR 行。
plugins {
    kotlin("jvm") version "2.2.0"
    application
}
kotlin { jvmToolchain(21) }
sourceSets {
    main {
        kotlin {
            srcDir("../../../app/src/main/java")
            include(
                "OcrDump.kt",
                "com/youma/app/engine/ppocr/CtcDecoder.kt", "com/youma/app/engine/ppocr/DbPostProcess.kt",
                "com/youma/app/engine/ppocr/OcrTypes.kt", "com/youma/app/engine/ppocr/PpOcrEngine.kt",
                "com/youma/app/engine/ppocr/WordGaps.kt",
                "com/youma/app/engine/genai/SemanticPrompt.kt",
                "com/youma/app/core/model/Sensitivity.kt",
            )
        }
    }
}
dependencies { implementation("com.microsoft.onnxruntime:onnxruntime:1.30.0") }
application { mainClass.set("com.youma.app.OcrDumpKt") }
tasks.named<JavaExec>("run") { workingDir = projectDir }
