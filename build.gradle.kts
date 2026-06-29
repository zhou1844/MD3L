import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "1.9.22"
    kotlin("plugin.serialization") version "1.9.22"
    id("org.jetbrains.compose") version "1.6.0"
}

group = "com.md3l"
version = "1.1.0"

dependencies {
    // Compose Desktop
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)

    // Kotlin Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.8.0")

    // Kotlin Serialization (JSON)
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    // Ktor Client/Server for HTTP, WebSocket, relay service
    implementation("io.ktor:ktor-client-core:2.3.8")
    implementation("io.ktor:ktor-client-cio:2.3.8")
    implementation("io.ktor:ktor-client-content-negotiation:2.3.8")
    implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.8")
    implementation("io.ktor:ktor-client-websockets:2.3.8")
    implementation("io.ktor:ktor-server-core:2.3.8")
    implementation("io.ktor:ktor-server-netty:2.3.8")
    implementation("io.ktor:ktor-server-websockets:2.3.8")
    implementation("io.ktor:ktor-server-content-negotiation:2.3.8")

    // Image loading
    implementation("media.kamel:kamel-image:0.9.3")
    implementation("io.ktor:ktor-client-java:2.3.8")

    // JNA for Windows COM interop (BedrockLaunchEngine UWP activation)
    implementation("net.java.dev.jna:jna:5.14.0")
    implementation("net.java.dev.jna:jna-platform:5.14.0")

    // Jsoup HTML Parser
    implementation("org.jsoup:jsoup:1.17.2")

    // SLF4J NOP binding（消除 "Failed to load class StaticLoggerBinder" 警告）
    // 应用已自建 AppLogger，不需 SLF4J 输出日志
    implementation("org.slf4j:slf4j-nop:2.0.9")

}

compose.desktop {
    application {
        mainClass = "launcher.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Exe)
            packageName = "MD3L"
            packageVersion = "1.1.0"
            description = "MD3L - A modern Minecraft launcher by @yunoniaodudu"
            vendor = "MD3L"

            windows {
                dirChooser = true
                menuGroup = "MD3L"
                upgradeUuid = "e4c8b3a1-5f2d-4e9a-b8c7-1a2b3c4d5e6f"
                iconFile.set(project.file("src/main/resources/app_icon.ico"))
            }

            jvmArgs += listOf("-Dfile.encoding=UTF-8")
        }
    }
}

kotlin {
    jvmToolchain(17)
}
