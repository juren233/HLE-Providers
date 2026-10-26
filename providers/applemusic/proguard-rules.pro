# The Pack is loaded by the core module through an InMemoryDexClassLoader whose parent is the
# core class loader (parent-first). Only the Pack's own com.juren233.hle.providers.* code may be
# renamed or removed; everything the core also defines must keep its binary identity.

# Core finds the entry through manifest.entryClass (INSTANCE field or no-arg constructor).
-keep class com.juren233.hle.providers.applemusic.AppleMusicPluginEntry { *; }

# Shared ABI sources are byte-identical copies of the core classes and resolve to the core's
# kept versions at runtime; renaming or stripping members here would desynchronize call sites.
-keep class com.juren233.hyperlyricsenhanced.** { *; }
-keep interface com.juren233.hyperlyricsenhanced.** { *; }

# Pack classes unreachable from the core must not collide with the core's own obfuscated names
# (parent-first lookup would silently bind to the core's class), so move them under a prefix
# that only this Pack uses.
-repackageclasses com.juren233.hle.providers.applemusic.o

# Optimization is left off: the Pack resolves Apple Music members by verified string
# descriptors, and the shipped behavior must stay the one verified on a real device.
-dontoptimize

# kotlinx.serialization is compileOnly here and ships no consumer rules; serializers are looked
# up through Companion.serializer() and generated $$serializer classes.
-keepattributes *Annotation*, InnerClasses, Signature
-keep @kotlinx.serialization.Serializable class com.juren233.hle.providers.applemusic.** { *; }
-keep class com.juren233.hle.providers.applemusic.**$$serializer { *; }
-keepclassmembers class com.juren233.hle.providers.applemusic.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}

# compileOnly DexKit references FlatBuffers internally; both resolve from the core at runtime.
-dontwarn com.google.flatbuffers.**

# Keep stack traces retraceable with the release mapping.txt; renaming the source file to one
# shared string makes the line tables free (measured: 731,774 -> 725,819 bytes gzip).
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
