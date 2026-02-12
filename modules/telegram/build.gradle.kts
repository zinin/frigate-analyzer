plugins {
    id("org.springframework.boot")
}

tasks.bootJar {
    enabled = false
}

dependencies {
    implementation(project(":frigate-analyzer-common"))
    implementation(project(":frigate-analyzer-model"))

    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-data-r2dbc")

    // Telegram Bot API
    implementation("dev.inmo:tgbotapi:${property("ktgbotapiVersion")}")

    // Logging
    implementation("io.github.oshai:kotlin-logging-jvm:${property("kotlinLoggingVersion")}")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor")
}
