# SecureChat Android SDK — Consumer ProGuard Rules
# ═══════════════════════════════════════════════════════════════
# 这些规则会传递给引入 SDK 的 App 模块。
# 目的：在 release 构建时剥离 SDK 内部调试日志，同时保持公共 API 可用。

# ── Release 剥离调试日志 ────────────────────────────────────────
# 移除 Log.d / Log.v / Log.i（保留 Log.w / Log.e，便于上报生产故障）
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
}

# ── 保留 SDK 公共 API ───────────────────────────────────────────
-keep class space.securechat.sdk.** { *; }
-keep interface space.securechat.sdk.** { *; }

# ── BouncyCastle（Ed25519 / X25519 / AES-GCM 反射使用）─────────
-keep class org.bouncycastle.** { *; }
-keep interface org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# ── bitcoinj（MnemonicCode 运行时反射）─────────────────────────
-keep class org.bitcoinj.crypto.MnemonicCode { *; }
-dontwarn org.bitcoinj.**

# ── Moshi / Retrofit 注解 ──────────────────────────────────────
-keep @com.squareup.moshi.JsonClass class *
-keepclassmembers class * {
    @com.squareup.moshi.* *;
}
-dontwarn retrofit2.**
-dontwarn okhttp3.**

# ── Room 实体（KSP 生成类需保留）───────────────────────────────
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Database class * { *; }
-keep class androidx.room.** { *; }
