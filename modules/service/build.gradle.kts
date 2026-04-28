plugins {
    id("org.springframework.boot")
    kotlin("kapt")
}

tasks.bootJar {
    enabled = false
}

dependencies {
    implementation(project(":frigate-analyzer-common"))
    api(project(":frigate-analyzer-model"))

    implementation(libs.spring.boot.starter)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.data.r2dbc)
    implementation(libs.uuid.creator)
    implementation(libs.kotlin.logging)
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.reactor)
    implementation(libs.bundles.mapstruct)
    implementation(libs.jakarta.inject.api)

    kapt(libs.mapstruct.processor)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.mockk)

    testRuntimeOnly(libs.junit.platform.launcher)
}

kapt {
    arguments {
        arg("mapstruct.defaultComponentModel", "spring")
        arg("mapstruct.unmappedTargetPolicy", "error")
    }
}
