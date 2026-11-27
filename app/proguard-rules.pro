# 课表网页导入：网页里的 JS 调这些方法
-keepclassmembers class com.zhishi.timetable.** { @android.webkit.JavascriptInterface <methods>; }
-dontwarn org.jspecify.**
-dontwarn com.google.re2j.**
# 离线语音：sherpa-onnx 的 JNI 按名字读这些 Kotlin 类的字段 / 构造函数，不能混淆或删掉
-keep class com.k2fsa.sherpa.onnx.** { *; }
# Commons Compress 里用不到的可选压缩格式（xz / brotli / zstd 等）引用的类
-dontwarn org.tukaani.xz.**
-dontwarn org.brotli.dec.**
-dontwarn com.github.luben.zstd.**
-dontwarn org.objectweb.asm.**
-dontwarn org.osgi.framework.**
-dontwarn org.apache.commons.compress.harmony.**
