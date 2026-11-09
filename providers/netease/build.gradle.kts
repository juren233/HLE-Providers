plugins {
    id("com.android.application")
}

android {
    namespace = "com.juren233.hle.providers.netease"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.juren233.hle.providers.pack.netease"
        minSdk = 33
        targetSdk = 37
        versionCode = 31
        versionName = "1.0.28"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        buildConfig = true
    }

    // 本地文件歌词（媒体库定位、内嵌标签读取、LRC 解析）由椒盐音乐、QQ 音乐与网易云音乐共用，源码只放一份
    sourceSets {
        getByName("main").kotlin.directories.add(
            rootProject.file("shared/local-lyrics/src/main/kotlin").path,
        )
        getByName("test").kotlin.directories.add(
            rootProject.file("shared/local-lyrics/src/test/kotlin").path,
        )
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    compileOnly("io.github.proify.lyricon:provider:0.1.70")
    compileOnly("org.jetbrains.kotlin:kotlin-stdlib:2.3.21")
    compileOnly("org.luckypray:dexkit:2.2.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlin:kotlin-stdlib:2.3.21")
}
