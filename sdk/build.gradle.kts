// sdk/build.gradle.kts
group = "space.securechat"
version = "0.3.0"

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
}

android {
    namespace = "space.securechat.sdk"
    compileSdk = 34

    defaultConfig {
        minSdk = 26          // Android 8.0+（AES-GCM 原生支持）
        targetSdk = 34
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    packaging {
        resources {
            // BouncyCastle / bitcoinj META-INF 签名文件冲突
            excludes += setOf(
                "META-INF/BCKEY.DSA",
                "META-INF/BCKEY.SF",
                "META-INF/BCPROV.DSA",
                "META-INF/BCPROV.SF",
                "META-INF/*.SF",
                "META-INF/*.DSA",
                "META-INF/*.RSA",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
            )
            pickFirsts += setOf(
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
            )
        }
    }
}

dependencies {
    // 协程
    api(libs.kotlinx.coroutines.android)
    // HTTP + WebSocket
    api(libs.okhttp)
    implementation(libs.okhttp.logging)
    api(libs.retrofit)
    implementation(libs.retrofit.moshi)
    implementation(libs.moshi.kotlin)
    // Room 数据库（持久化消息和身份）
    api(libs.room.runtime)
    api(libs.room.ktx)
    ksp(libs.room.compiler)
    // 密码学（Bouncy Castle：Ed25519 / X25519 / AES-GCM / HKDF）
    api(libs.bouncycastle.prov)
    implementation(libs.bouncycastle.pkix)
    // BIP-39 助记词（仅用 MnemonicCode 类）
    // 排除 bitcoinj 传递的旧版 BouncyCastle（与 bcprov-jdk18on 类名冲突）
    implementation(libs.bitcoinj.core) {
        exclude(group = "org.bouncycastle")
        exclude(group = "com.google.protobuf")
        exclude(group = "net.jcip")
    }
    // bitcoinj 运行时强依赖 Guava（Preconditions / Sha256Hash 初始化），严禁移除
    implementation("com.google.guava:guava:33.0.0-android")

    // 测试
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
