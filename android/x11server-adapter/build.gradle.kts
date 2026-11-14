plugins {
    id("com.android.library")
}

android {
    namespace = "au.com.darkside.xserver"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
    }

    sourceSets {
        getByName("main") {
            // Keep the pinned upstream X11 server source and drawable resources together.
            java.srcDir("../x11server/library/src/main/java")
            res.srcDir("../x11server/library/src/main/res")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
