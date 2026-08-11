# R8 rules for the release build.
#
# The app has almost no reflection, so nearly everything can be renamed. The exceptions below are the
# things Android itself looks up by name at runtime — get these wrong and the app compiles, installs,
# and crashes on launch.

# Activities are named as strings in AndroidManifest.xml, so their class names must survive.
-keep class com.abacus.dualscreen.HomeActivity { *; }
-keep class com.abacus.dualscreen.ScreenActivity { *; }

# View binding classes are generated and instantiated reflectively by the framework.
-keep class com.abacus.dualscreen.databinding.** { *; }

# Anything reached only from XML (custom views, onClick handlers) would need keeping too. There are
# none today; this is here so the next person adding one knows where it goes.

# Strip logging from the release build: it costs nothing to remove and leaks internal detail.
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
}

# Hide the original source file and line numbers in stack traces. Keeping LineNumberTable plus the
# mapping file would give better crash reports; this trades that for less information in the binary.
-renamesourcefileattribute SourceFile
