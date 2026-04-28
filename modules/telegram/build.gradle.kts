plugins {
    id("org.springframework.boot")
}

tasks.bootJar {
    enabled = false
}

dependencies {
    implementation(project(":frigate-analyzer-common"))
    implementation(project(":frigate-analyzer-model"))
    implementation(project(":frigate-analyzer-service"))
    implementation(project(":frigate-analyzer-ai-description"))

    implementation(libs.spring.boot.starter)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.boot.starter.data.r2dbc)
    implementation(libs.ktgbotapi)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.kotlin.logging)
    implementation(libs.bundles.coroutines)

    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.mockk)
}
