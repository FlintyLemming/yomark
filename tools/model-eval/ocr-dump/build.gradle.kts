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
                "OcrDump.kt", "RuleProto.kt", "OcrBench.kt", "EnRules.kt", "EnProto.kt",
                "moe/flinty/yomark/engine/ppocr/CtcDecoder.kt", "moe/flinty/yomark/engine/ppocr/DbPostProcess.kt",
                "moe/flinty/yomark/engine/ppocr/OcrTypes.kt", "moe/flinty/yomark/engine/ppocr/PpOcrEngine.kt",
                "moe/flinty/yomark/engine/ppocr/WordGaps.kt",
                "moe/flinty/yomark/engine/genai/SemanticPrompt.kt",
                "moe/flinty/yomark/core/model/Sensitivity.kt",
                // 规则原型要对照出厂规则表
                "moe/flinty/yomark/rules/Rule.kt", "moe/flinty/yomark/rules/DefaultRuleSet.kt",
                "moe/flinty/yomark/rules/PhoneRule.kt", "moe/flinty/yomark/rules/NameBeforePhone.kt",
                "moe/flinty/yomark/rules/LabeledField.kt", "moe/flinty/yomark/rules/HanView.kt",
                "moe/flinty/yomark/rules/PersonNameRecognizer.kt", "moe/flinty/yomark/rules/NameAnchor.kt",
                "moe/flinty/yomark/rules/AddressShape.kt", "moe/flinty/yomark/rules/DateTimeRule.kt",
                "moe/flinty/yomark/rules/validator/Checksums.kt", "moe/flinty/yomark/rules/validator/KnownTlds.kt",
            )
        }
    }
}
dependencies {
    implementation("com.microsoft.onnxruntime:onnxruntime:1.30.0")
    implementation("com.googlecode.libphonenumber:libphonenumber:8.13.52")
    implementation("com.hankcs:hanlp:portable-1.8.4")   // 只有规则原型里的人名识别对照用
}
application { mainClass.set(providers.gradleProperty("main").orElse("moe.flinty.yomark.OcrDumpKt")) }
tasks.named<JavaExec>("run") { workingDir = projectDir }
