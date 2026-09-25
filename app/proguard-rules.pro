# يجب حفظ الـ annotation حتى يعرف WebView أي الدوال مكشوفة لـ JS
-keepattributes JavascriptInterface

-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
