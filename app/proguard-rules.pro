# kotlinx.serialization ships its own rules; keep JNI entry points of the torrent engine.
-keepclasseswithmembernames class * { native <methods>; }
-keep class com.synrg.harbor.torrent.NativeEngine { *; }
