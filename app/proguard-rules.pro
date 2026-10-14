# HanLP：IO 适配器按类名反射创建，部分词典结构用 Java 序列化读写。整包保留，不混淆、不裁剪
-keep class com.hankcs.hanlp.** { *; }
-dontwarn com.hankcs.hanlp.**

# ONNX Runtime：JNI 层按写死的类名回调 Java（FindClass("ai/onnxruntime/TensorInfo") 等）。
# AAR 自带的 consumer 规则只 keep 了遥测那几个类，其余被 R8 改名、合并后 OrtSession.run 找不到类，
# ART 直接 abort 整个进程（runCatching 拦不住），表现为识别时闪退回首页。整包保留
-keep class ai.onnxruntime.** { *; }

# ML Kit 的 TextRegistrar、FaceRegistrar、BarcodeRegistrar 等由 Firebase 的 ComponentDiscovery
# 按 manifest 里登记的类名反射调无参构造创建。firebase-components 16.1.0 自带的规则只有
# -keep class * implements ComponentRegistrar，AGP 9 起 R8 默认严格全模式，这样写不再顺带保留无参构造，
# release 包里这几个类的构造被删光，ML Kit 初始化失败。补上 <init>
-keep class * implements com.google.firebase.components.ComponentRegistrar { <init>(); }
