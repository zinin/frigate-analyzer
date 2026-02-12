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

    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.data:spring-data-r2dbc")

    implementation("com.github.f4b6a3:uuid-creator:${property("uuidCreatorVersion")}")

    implementation("io.github.oshai:kotlin-logging-jvm:${property("kotlinLoggingVersion")}")

    implementation("org.mapstruct:mapstruct:${property("mapstructVersion")}")
    implementation("org.mapstruct.extensions.spring:mapstruct-spring-extensions:${property("mapstructSpringExtensionsVersion")}")
    implementation("jakarta.inject:jakarta.inject-api:${property("jakartaInjectApiVersion")}")

    kapt("org.mapstruct:mapstruct-processor:${property("mapstructVersion")}")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")

    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kapt {
    arguments {
        arg("mapstruct.defaultComponentModel", "spring")
        arg("mapstruct.unmappedTargetPolicy", "error")
    }
}
