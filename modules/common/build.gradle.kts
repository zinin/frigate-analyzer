plugins {
    id("org.springframework.boot")
}

tasks.bootJar {
    enabled = false
}

dependencies {
    implementation(libs.spring.boot.starter)
    implementation(libs.uuid.creator)
    implementation(libs.kotlin.logging)
}
