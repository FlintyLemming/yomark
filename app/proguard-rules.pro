# HanLP：IO 适配器按类名反射创建，部分词典结构用 Java 序列化读写。整包保留，不混淆、不裁剪
-keep class com.hankcs.hanlp.** { *; }
-dontwarn com.hankcs.hanlp.**

# ONNX Runtime：JNI 层按写死的类名回调 Java（FindClass("ai/onnxruntime/TensorInfo") 等）。
# AAR 自带的 consumer 规则只 keep 了遥测那几个类，其余被 R8 改名、合并后 OrtSession.run 找不到类，
# ART 直接 abort 整个进程（runCatching 拦不住），表现为识别时闪退回首页。整包保留
-keep class ai.onnxruntime.** { *; }
