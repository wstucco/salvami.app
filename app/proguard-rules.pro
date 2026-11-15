# youtubedl-android ships no consumer rules; it loads native code and reflects on its own classes.
-keep class com.yausername.** { *; }
-dontwarn com.fasterxml.jackson.**
-dontwarn org.apache.commons.**
