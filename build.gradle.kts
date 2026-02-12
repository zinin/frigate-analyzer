plugins {
    kotlin("jvm") version "2.3.10"
    kotlin("plugin.spring") version "2.3.10"
    id("org.springframework.boot") version "4.0.2" apply false
    id("io.spring.dependency-management") version "1.1.7" apply false
    id("maven-publish")
    id("org.jlleitschuh.gradle.ktlint") version "14.0.1"
    id("jacoco")
    id("com.gorylenko.gradle-git-properties") version "2.5.6" apply false
    kotlin("kapt") version "2.3.10" apply false
}

allprojects {
    group = "ru.zinin"
    version = "0.0.1-SNAPSHOT"

    repositories {
        mavenCentral()
    }
}

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "org.jetbrains.kotlin.plugin.spring")
    apply(plugin = "io.spring.dependency-management")
    apply(plugin = "maven-publish")
    apply(plugin = "org.jlleitschuh.gradle.ktlint")
    apply(plugin = "jacoco")
    apply(plugin = "org.jlleitschuh.gradle.ktlint")
    apply(plugin = "com.gorylenko.gradle-git-properties")

    configurations.all {
        resolutionStrategy.cacheChangingModulesFor(0, "seconds")
    }

    java {
        toolchain {
            languageVersion = JavaLanguageVersion.of(21)
        }
    }

    kotlin {
        compilerOptions {
            freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
        }
    }

    ktlint {
        version.set("1.8.0")

        android.set(false)
        verbose.set(true)
        reporters {
            reporter(org.jlleitschuh.gradle.ktlint.reporter.ReporterType.HTML)
            reporter(org.jlleitschuh.gradle.ktlint.reporter.ReporterType.CHECKSTYLE)
        }
        filter {
            exclude { element -> element.file.path.contains("generated/") }
        }
    }
    jacoco {
        toolVersion = "0.8.14"
    }

    tasks.jacocoTestReport {
        reports {
            xml.required.set(true)
            html.required.set(true)
        }

//      TODO
        classDirectories.setFrom(
            sourceSets.main.get().output.asFileTree.matching {
                exclude("ru/zinin/jaxb/**/*.class", "ru/zinin/ws/**/*.class")
            },
        )
    }

    tasks.jacocoTestCoverageVerification {
        classDirectories.setFrom(
            sourceSets.main.get().output.asFileTree.matching {
                exclude("ebp/esmv/emulator/jaxb/**/*.class", "ebp/esmv/emulator/ws/**/*.class")
            },
        )

        violationRules {
            rule {
                limit {
                    minimum = BigDecimal.valueOf(0.01)
                }
            }
        }
    }

    tasks.test {
        finalizedBy(tasks.jacocoTestReport)
    }

    tasks.check {
        dependsOn(tasks.jacocoTestCoverageVerification)
    }

    tasks.withType<Test> {
        useJUnitPlatform()
    }

    tasks.withType<JavaCompile> {
        options.compilerArgs.addAll(listOf("-Xlint:unchecked", "-Xlint:deprecation"))
    }
}

extra["kotlinLoggingVersion"] = "7.0.14"
extra["postgresqlVersion"] = "42.7.9"
extra["mapstructVersion"] = "1.6.3"
extra["mapstructSpringExtensionsVersion"] = "2.0.0"
extra["jakartaInjectApiVersion"] = "2.0.1"
extra["jakartaMailApiVersion"] = "2.1.5"
extra["uuidCreatorVersion"] = "6.1.1"
extra["testcontainersVersion"] = "2.0.3"
extra["openApiStarterVersion"] = "3.0.1"
extra["ktgbotapiVersion"] = "30.0.2"
extra["mockwebserverVersion"] = "5.3.2"
extra["mockkVersion"] = "1.14.9"
extra["liquibaseCoreVersion"] = "5.0.1"
extra["picocliVersion"] = "4.7.7"
extra["commonsLang3Version"] = "3.20.0"
