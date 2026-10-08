plugins {
    alias(libs.plugins.android.library) apply false
}

apply(from = "scripts/build-inputs.gradle")

// One Bouncy Castle version across every module, because the request arrives in more places
// than any single module can see. The patcher pins 1.77 and the Android build tools ask for
// 1.79, and both are inside CVE-2025-8916 (1.44 to 1.79) and CVE-2026-5588 (1.49 to 1.84);
// the reviewed release is the one gradle/libs.versions.toml pins, which says why it is that
// one. None of this reaches the payload injected into Instagram. It is the build and signing
// classpath, and the repository's rule is that a known-affected component does not stay in a
// reproducible graph.
//
// Per module this was applied twice and still missed three graphs: :extensions:shared's release
// runtime classpath, which has no force of its own, and two configurations the Android plugin
// makes for itself. The settings classpath is forced separately in settings.gradle.kts, which
// resolves before this file exists.
val reviewedBouncyCastle = libs.versions.bouncycastle.get()
val reviewedGuava = libs.versions.guava.get()
val reviewedNetty = libs.versions.netty.get()
val reviewedCommonsLang = libs.versions.commons.lang.tooling.get()
val reviewedHttpClient = libs.versions.httpclient.tooling.get()
allprojects {
    configurations.configureEach {
        // Only AGP's two host test-tool graphs bring Netty here. Don't change a future
        // payload or native transport dependency just because it shares the group.
        val isUtp = name == "_internal-unified-test-platform-core" ||
                name == "_internal-unified-test-platform-android-test-plugin-host-emulator-control"
        val isUtpResultListener = name == "_internal-unified-test-platform-android-test-plugin-result-listener-gradle"
        resolutionStrategy.eachDependency {
            if (requested.group == "org.bouncycastle") {
                useVersion(reviewedBouncyCastle)
                because("The build classpath must use the reviewed Bouncy Castle release.")
            }
            if (requested.group == "com.google.guava" && requested.name == "guava") {
                // Include tool-created test graphs in modules without a direct Guava request.
                useVersion(reviewedGuava)
                because("The resolved dependency audit must use the reviewed Guava release.")
            }
            // Netty 4.1 reaches end of life on 2027-07-01, and these graphs can't leave it yet. AGP's
            // emulator control asks for grpc-netty 1.69.1, built against Netty 4.1, in every release
            // checked on 2026-10-07: emulator proto 32.4.1 (AGP 9.4.1, the newest stable) and
            // 32.5.0-alpha08. grpc-netty itself is on 4.2 now (1.84.0 takes 4.2.16), so the move waits
            // for an AGP whose emulator proto takes such a gRPC. Until then this keeps the newest
            // patched 4.1 release. Don't force 4.2 here: grpc-netty 1.69.1 is built for the 4.1 API.
            if (isUtp && requested.group == "io.netty" && requested.version?.startsWith("4.1.") == true) {
                useVersion(reviewedNetty)
                because("AGP host test tools must use the reviewed Netty 4.1 fixes.")
            }
            if (isUtpResultListener && requested.group == "org.apache.commons" && requested.name == "commons-lang3") {
                useVersion(reviewedCommonsLang)
                because("UTP result tools must not retain CVE-2025-48924.")
            }
            if (isUtpResultListener && requested.group == "org.apache.httpcomponents" && requested.name == "httpclient" &&
                requested.version?.startsWith("4.5.") == true) {
                useVersion(reviewedHttpClient)
                because("UTP result tools must use the compatible fix for CVE-2020-13956.")
            }
        }
    }
}
