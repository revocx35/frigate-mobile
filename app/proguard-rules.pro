# kotlinx.serialization, Navigation, OkHttp, coroutines and Compose ship their own R8 rules; the app needs
# no extra keep rules (models are (de)serialized through generated serializers, not reflection).

# Shrink and optimize, but don't rename: stack traces in the in-app crash reports stay readable without a
# mapping file, and nothing can break because a class or member name changed.
-dontobfuscate

# libwebrtc (two-way audio) calls into its Java classes (and jni_zero, its JNI glue) from native code by name: keep
# them whole. Without jni_zero the app aborts in JNI_OnLoad (seen in the 1.3.0 e2e run).
-keep class org.webrtc.** { *; }
-keep class org.jni_zero.** { *; }
-dontwarn org.jni_zero.JniZeroJni
