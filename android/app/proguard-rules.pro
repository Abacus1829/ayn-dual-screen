# The app is a thin WebView shell with no reflection, so the defaults are enough.
# Keep the JS bridge rule handy in case a native<->page bridge is added later.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
