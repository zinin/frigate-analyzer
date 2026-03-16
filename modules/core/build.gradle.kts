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
        mavenBom(libs.testcontainers.bom)
    }
}

dependencies {
    implementation(project(":frigate-analyzer-common"))
    implementation(project(":frigate-analyzer-service"))
    implementation(project(":frigate-analyzer-telegram"))

    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.boot.starter.webflux)
    implementation(libs.spring.boot.starter.data.r2dbc)
    implementation(libs.spring.boot.starter.log4j2)

    implementation(libs.openapi.starter.webflux.ui)
    implementation(libs.openapi.starter.common)

    implementation(libs.r2dbc.postgresql)
    implementation(libs.reactor.kotlin.extensions)
    implementation(libs.kotlin.reflect)
    implementation(libs.bundles.coroutines)
    implementation(libs.bundles.jackson)
    implementation(libs.kotlin.logging)
    implementation(libs.postgresql)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.boot.starter.webclient.test)
    testImplementation(libs.spring.boot.starter.webflux.test)
    testImplementation(libs.spring.boot.starter.log4j2)
    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.bundles.testcontainers)
    testImplementation(libs.mockwebserver)
    testImplementation(libs.mockk)

    testRuntimeOnly(libs.junit.platform.launcher)

    "liquibase"(libs.liquibase.core)
    "liquibase"(libs.postgresql)
    "liquibase"(libs.picocli)
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
