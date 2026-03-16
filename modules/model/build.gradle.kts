plugins {
    id("org.springframework.boot")
}

tasks.bootJar {
    enabled = false
}

dependencies {
    implementation(project(":frigate-analyzer-common"))

    implementation(libs.spring.boot.starter)
    implementation(libs.jackson.annotations)
    implementation(libs.spring.data.r2dbc)
    implementation(libs.uuid.creator)
    implementation(libs.kotlin.logging)
}
