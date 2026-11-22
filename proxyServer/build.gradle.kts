plugins {
    alias(libs.plugins.jetbrains.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.cors)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.slf4j.api)
    implementation(libs.onnxruntime)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.junit)
}

tasks.register<JavaExec>("runProxy") {
    group = "application"
    description = "Run the standalone TETUKO proxy on 127.0.0.1:14200 (optional port argument)."
    mainClass.set("id.tetuko.proxy.ProxyMainKt")
    classpath = sourceSets["main"].runtimeClasspath
}
