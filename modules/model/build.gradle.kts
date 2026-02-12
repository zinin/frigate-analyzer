plugins {
    id("org.springframework.boot")
}

tasks.bootJar {
    enabled = false
}

dependencies {
    implementation(project(":frigate-analyzer-common"))

    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.data:spring-data-r2dbc")

    implementation("com.github.f4b6a3:uuid-creator:${property("uuidCreatorVersion")}")

    implementation("io.github.oshai:kotlin-logging-jvm:${property("kotlinLoggingVersion")}")
}
