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
                "OcrDump.kt", "RuleProto.kt",
                "com/youma/app/engine/ppocr/CtcDecoder.kt", "com/youma/app/engine/ppocr/DbPostProcess.kt",
                "com/youma/app/engine/ppocr/OcrTypes.kt", "com/youma/app/engine/ppocr/PpOcrEngine.kt",
                "com/youma/app/engine/ppocr/WordGaps.kt",
                "com/youma/app/engine/genai/SemanticPrompt.kt",
                "com/youma/app/core/model/Sensitivity.kt",
                // 规则原型要对照出厂规则表
                "com/youma/app/rules/Rule.kt", "com/youma/app/rules/DefaultRuleSet.kt",
                "com/youma/app/rules/PhoneRule.kt", "com/youma/app/rules/NameBeforePhone.kt",
                "com/youma/app/rules/LabeledField.kt", "com/youma/app/rules/HanView.kt",
                "com/youma/app/rules/AddressShape.kt", "com/youma/app/rules/DateTimeRule.kt",
                "com/youma/app/rules/validator/Checksums.kt", "com/youma/app/rules/validator/KnownTlds.kt",
            )
        }
    }
}
dependencies {
    implementation("com.microsoft.onnxruntime:onnxruntime:1.30.0")
    implementation("com.googlecode.libphonenumber:libphonenumber:8.13.52")
    implementation("com.hankcs:hanlp:portable-1.8.4")   // 只有规则原型里的人名识别对照用
}
application { mainClass.set(providers.gradleProperty("main").orElse("com.youma.app.OcrDumpKt")) }
tasks.named<JavaExec>("run") { workingDir = projectDir }
