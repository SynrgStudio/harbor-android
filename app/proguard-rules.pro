# kotlinx.serialization ships its own rules; keep JNI entry points of the torrent engine.
-keepclasseswithmembernames class * { native <methods>; }
-keep class com.synrg.harbor.torrent.NativeEngine { *; }
# Named only in the manifest (Cast's options provider), so R8 can't see it's used.
-keep class com.synrg.harbor.cast.CastOptionsProvider { *; }
