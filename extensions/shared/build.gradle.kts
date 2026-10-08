dependencies {
    implementation(project(":extensions:shared:library"))
}

extension {
    name = "extensions/shared.mpe"
}

android {
    // Unique per extension to avoid install-time package collisions.
    namespace = "app.hushgram.extension.shared"

    defaultConfig {
        // The library it carries runs only inside Instagram 450, which declares API 28.
        minSdk = 28
    }
}
