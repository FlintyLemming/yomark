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
                "com/yomark/app/engine/ppocr/CtcDecoder.kt", "com/yomark/app/engine/ppocr/DbPostProcess.kt",
                "com/yomark/app/engine/ppocr/OcrTypes.kt", "com/yomark/app/engine/ppocr/PpOcrEngine.kt",
                "com/yomark/app/engine/ppocr/WordGaps.kt",
                "com/yomark/app/engine/genai/SemanticPrompt.kt",
                "com/yomark/app/core/model/Sensitivity.kt",
                // 规则原型要对照出厂规则表
                "com/yomark/app/rules/Rule.kt", "com/yomark/app/rules/DefaultRuleSet.kt",
                "com/yomark/app/rules/PhoneRule.kt", "com/yomark/app/rules/NameBeforePhone.kt",
                "com/yomark/app/rules/LabeledField.kt", "com/yomark/app/rules/HanView.kt",
                "com/yomark/app/rules/PersonNameRecognizer.kt",
                "com/yomark/app/rules/AddressShape.kt", "com/yomark/app/rules/DateTimeRule.kt",
                "com/yomark/app/rules/validator/Checksums.kt", "com/yomark/app/rules/validator/KnownTlds.kt",
            )
        }
    }
}
dependencies {
    implementation("com.microsoft.onnxruntime:onnxruntime:1.30.0")
    implementation("com.googlecode.libphonenumber:libphonenumber:8.13.52")
    implementation("com.hankcs:hanlp:portable-1.8.4")   // 只有规则原型里的人名识别对照用
}
application { mainClass.set(providers.gradleProperty("main").orElse("com.yomark.app.OcrDumpKt")) }
tasks.named<JavaExec>("run") { workingDir = projectDir }
