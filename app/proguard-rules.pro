# youtubedl-android ships no consumer rules; it loads native code and reflects on its own classes.
-keep class com.yausername.** { *; }
-dontwarn com.fasterxml.jackson.**
-dontwarn org.apache.commons.**
# It unpacks Python with commons-compress, which registers its zip extra-field parsers by
# reflection (ExtraFieldUtils). Without this, R8 strips their constructors and unpacking fails.
-keep class org.apache.commons.compress.archivers.zip.** { *; }
