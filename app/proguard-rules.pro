# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in /sdk/tools/proguard/proguard-android-optimize.txt

# Keep native methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep Hilt generated classes
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keep class * extends dagger.hilt.android.internal.managers.ComponentSupplier { *; }

# Keep AudioEngine
-keep class com.binaural.core.audio.** { *; }
