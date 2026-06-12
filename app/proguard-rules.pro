# Add project specific ProGuard rules here.
-keepattributes Signature
-keepattributes *Annotation*

# Keep OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**

# Keep our crypto classes (in case minify is enabled later)
-keep class cn.cyhkbl.zjuautoconnect.SrunCrypto { *; }
