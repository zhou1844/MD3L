import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "1.9.22"
    kotlin("plugin.serialization") version "1.9.22"
    id("org.jetbrains.compose") version "1.6.0"
}

group = "com.md3l"
version = "1.4.4.a"

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

    // Jsoup HTML Parser
    implementation("org.jsoup:jsoup:1.17.2")

    // SLF4J NOP binding
    implementation("org.slf4j:slf4j-nop:2.0.9")
}

compose.desktop {
    application {
        mainClass = "launcher.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Deb, TargetFormat.Rpm, TargetFormat.AppImage)
            packageName = "MD3L"
            packageVersion = "1.4.4.a"
            description = "MD3L - A modern Minecraft launcher (Linux Edition)"
            vendor = "MD3L"

            linux {
                packageName = "md3l"
                iconFile.set(project.file("src/main/resources/app_icon.png"))
                menuGroup = "Game"
            }

            jvmArgs += listOf("-Dfile.encoding=UTF-8")

            // 添加 java.net.http 模块（HttpClient 连接池），确保 jlink 包含此模块
            modules("java.net.http")
        }
    }
}

kotlin {
    jvmToolchain(17)
}

