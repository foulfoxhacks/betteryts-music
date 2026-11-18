# JNI entry point into the Rust core (libbetteryts_core.so).
-keepclasseswithmembernames class * {
    native <methods>;
}

# Methods the YouTube player page calls through addJavascriptInterface.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
