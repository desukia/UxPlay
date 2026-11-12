# Keep JNI methods
-keepclasseswithmembernames class * {
    native <methods>;
}

-keep class com.appletv.airplay.engine.** { *; }
-keep class com.appletv.airplay.model.** { *; }
