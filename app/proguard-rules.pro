# Allow R8 to proceed with missing classes (JSch references JNA, GSSAPI, SLF4J,
# Log4j2, and Unix sockets which are unavailable on Android)
-ignorewarnings

# Defensive: if any future AGP / library bump adds an
# `-assumenosideeffects class android.util.Log { ... }` rule (which
# would strip log calls entirely from release builds), pin our
# critical Log statements here. As of AGP 8.9.1's
# proguard-android-optimize.txt no such rule ships.

# Keep crypto classes
-keep class javax.crypto.** { *; }
-keep class java.security.** { *; }
-keep class org.bouncycastle.** { *; }
-keep class com.google.crypto.tink.** { *; }

# Keep JSch
-keep class com.jcraft.jsch.** { *; }

# JSch optional dependencies not available on Android
-dontwarn org.apache.logging.log4j.**
-dontwarn org.slf4j.**
-dontwarn org.ietf.jgss.**
-dontwarn org.newsclub.net.unix.**
-dontwarn javax.naming.**
# Keep JNA — native JNI accesses Pointer.peer field by name (needed for IronRDP)
-keep class com.sun.jna.** { *; }
-dontwarn com.sun.jna.platform.win32.**
-dontwarn com.jcraft.jsch.PageantConnector
-dontwarn com.jcraft.jsch.Log4j2Logger
-dontwarn com.jcraft.jsch.Slf4jLogger
-dontwarn com.jcraft.jsch.jgss.**
-dontwarn com.jcraft.jsch.JUnixSocketFactory

# Keep termlib classes — native JNI renderer accesses fields by name
-keep class org.connectbot.terminal.** { *; }

# sshlib's Ed25519 JCE provider asks for its OWN package name while installing
# itself:
#
#   Ed25519Provider.class.getPackage().getName()      (Ed25519Provider.setup)
#
# R8 flattens ~26k classes into the root package, and a class with no package
# returns null from getPackage() — so that line throws NullPointerException
# inside the provider's constructor, killing the connection immediately after
# ECDH_REPLY (#513). Keeping the name keeps the package, which is the whole
# point of this rule; the members are incidental.
#
# Only reached via getEd25519FallbackProvider(), i.e. when the platform's own
# Ed25519 is unusable — which is why it depends on the device rather than
# failing for everyone.
-keep class org.connectbot.sshlib.crypto.ed25519.Ed25519Provider { *; }

# Keep protobuf generated classes — protobuf-lite uses reflection on field names
-keep class * extends com.google.protobuf.GeneratedMessageLite { *; }
-keep class * extends com.google.protobuf.GeneratedMessageLite$Builder { *; }

# Keep the gomobile bindings — JNI native methods and Go runtime (the
# WireGuard/Tailscale/NetBird/OpenConnect tunnel bridges).
-keep class go.** { *; }
-keep class sh.haven.rclone.binding.** { *; }

# Keep Hilt generated classes
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }

# Keep JNA Structure subclasses — IronRDP (UniFFI-generated sh.haven.rdp.**)
# uses @Structure.FieldOrder("capacity", "len", ...) string literals that
# JNA resolves reflectively at runtime. R8 renames the @JvmField properties
# to single letters, Structure.deriveLayout() can't find them, and every
# RDP connection fails with "unknown or zero size (ensure all fields are
# public)" — issue #93.
-keep class * extends com.sun.jna.Structure {
    <fields>;
    <init>(...);
}
-keep class sh.haven.rdp.** { *; }

# Keep Shizuku API — Haven calls Shizuku only via reflection
# (Class.forName("rikka.shizuku.Shizuku").getMethod("pingBinder") etc).
# Without this, R8 prunes pingBinder / addBinderReceivedListenerSticky /
# checkSelfPermission / requestPermission / newProcess, and the reflection
# lookups throw NoSuchMethodException at runtime — issue #82.
-keep class rikka.shizuku.** { *; }
-keep interface rikka.shizuku.** { *; }
-keep class moe.shizuku.** { *; }
-keep interface moe.shizuku.** { *; }

