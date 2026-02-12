plugins {
    id("org.springframework.boot")
}

tasks.jar {
    enabled = false
}

springBoot {
    buildInfo()
}

tasks.bootJar {
    archiveFileName.set("${archiveBaseName.get()}.${archiveExtension.get()}")
}

configurations {
    compileOnly {
        extendsFrom(configurations.annotationProcessor.get())
    }
    all {
        exclude(group = "org.springframework.boot", module = "spring-boot-starter-logging")
    }
    create("liquibase")
}

dependencyManagement {
    imports {
        mavenBom("org.testcontainers:testcontainers-bom:${property("testcontainersVersion")}")
    }
}

dependencies {
    implementation(project(":frigate-analyzer-common"))
    implementation(project(":frigate-analyzer-service"))
    implementation(project(":frigate-analyzer-telegram"))

    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-webflux")
    implementation("org.springframework.boot:spring-boot-starter-data-r2dbc")
    implementation("org.springframework.boot:spring-boot-starter-log4j2")

    implementation("org.springdoc:springdoc-openapi-starter-webflux-ui:${property("openApiStarterVersion")}")
    implementation("org.springdoc:springdoc-openapi-starter-common:${property("openApiStarterVersion")}")

    implementation("org.postgresql:r2dbc-postgresql")

    implementation("io.projectreactor.kotlin:reactor-kotlin-extensions")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core")

    implementation("com.fasterxml.jackson.core:jackson-databind")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml")

    implementation("io.github.oshai:kotlin-logging-jvm:${property("kotlinLoggingVersion")}")

    implementation("com.fasterxml.jackson.core:jackson-databind")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml")

    implementation("org.postgresql:postgresql:${property("postgresqlVersion")}")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webclient-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webflux-test")
    testImplementation("org.springframework.boot:spring-boot-starter-log4j2")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("com.squareup.okhttp3:mockwebserver3:${property("mockwebserverVersion")}")
    testImplementation("io.mockk:mockk:${property("mockkVersion")}")

    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // Liquibase dependencies
    "liquibase"("org.liquibase:liquibase-core:${property("liquibaseCoreVersion")}")
    "liquibase"("org.postgresql:postgresql:${property("postgresqlVersion")}")
    "liquibase"("info.picocli:picocli:${property("picocliVersion")}")
}

// Liquibase configuration
val liquibaseDbHost = findProperty("liquibaseDbHost")?.toString() ?: "localhost"
val liquibaseDbPort = findProperty("liquibaseDbPort")?.toString() ?: "5432"
val liquibaseDbName = findProperty("liquibaseDbName")?.toString() ?: "frigate_analyzer"
val liquibaseDbUser = findProperty("liquibaseDbUser")?.toString() ?: "frigate_analyzer_rw"
val liquibaseDbPassword = findProperty("liquibaseDbPassword")?.toString() ?: "frigate_analyzer_rw"

tasks.register<JavaExec>("liquibaseUpdate") {
    group = "liquibase"
    description = "Run Liquibase update on local database"
    mainClass.set("liquibase.integration.commandline.LiquibaseCommandLine")
    classpath = configurations["liquibase"]
    args(
        "--changelog-file=../../docker/liquibase/migration/master_frigate_analyzer.xml",
        "--url=jdbc:postgresql://$liquibaseDbHost:$liquibaseDbPort/$liquibaseDbName",
        "--username=$liquibaseDbUser",
        "--password=$liquibaseDbPassword",
        "--log-level=info",
        "update",
    )
}

tasks.register<JavaExec>("liquibaseStatus") {
    group = "liquibase"
    description = "Show Liquibase migration status"
    mainClass.set("liquibase.integration.commandline.LiquibaseCommandLine")
    classpath = configurations["liquibase"]
    args(
        "--changelog-file=../../docker/liquibase/migration/master_frigate_analyzer.xml",
        "--url=jdbc:postgresql://$liquibaseDbHost:$liquibaseDbPort/$liquibaseDbName",
        "--username=$liquibaseDbUser",
        "--password=$liquibaseDbPassword",
        "status",
    )
}

tasks.register<JavaExec>("liquibaseValidate") {
    group = "liquibase"
    description = "Validate Liquibase changelog"
    mainClass.set("liquibase.integration.commandline.LiquibaseCommandLine")
    classpath = configurations["liquibase"]
    args(
        "--changelog-file=../../docker/liquibase/migration/master_frigate_analyzer.xml",
        "--url=jdbc:postgresql://$liquibaseDbHost:$liquibaseDbPort/$liquibaseDbName",
        "--username=$liquibaseDbUser",
        "--password=$liquibaseDbPassword",
        "validate",
    )
}
