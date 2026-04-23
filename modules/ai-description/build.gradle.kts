plugins {
    id("org.springframework.boot")
}

tasks.bootJar {
    enabled = false
}

dependencies {
    implementation(libs.spring.boot.starter)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.boot.autoconfigure)
    implementation(libs.kotlin.logging)
    implementation(libs.bundles.coroutines)
    implementation(libs.bundles.jackson)
    implementation(libs.spring.ai.claude.code.sdk)

    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.mockk)
}
