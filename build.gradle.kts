plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.spring.boot) apply false
    alias(libs.plugins.spring.dependency.management) apply false
    id("maven-publish")
    alias(libs.plugins.ktlint)
    id("jacoco")
    alias(libs.plugins.git.properties) apply false
    alias(libs.plugins.kotlin.kapt) apply false
}

fun gitVersion(): String {
    fun exec(vararg args: String): String? =
        try {
            val process =
                ProcessBuilder(*args)
                    .directory(rootDir)
                    .redirectErrorStream(true)
                    .start()
            val output =
                process.inputStream
                    .bufferedReader()
                    .readText()
                    .trim()
            if (process.waitFor() == 0 && output.isNotEmpty()) output else null
        } catch (_: Exception) {
            null
        }

    // На теге: точная версия тега
    exec("git", "describe", "--tags", "--exact-match")
        ?.removePrefix("v")
        ?.let { return it }

    // Между тегами: bump patch + SNAPSHOT
    exec("git", "describe", "--tags", "--abbrev=0")
        ?.removePrefix("v")
        ?.let { tag -> Regex("""^(\d+)\.(\d+)\.(\d+)$""").matchEntire(tag) }
        ?.let { match ->
            val (major, minor, patch) = match.destructured
            return "$major.$minor.${patch.toInt() + 1}-SNAPSHOT"
        }

    return "0.0.1-SNAPSHOT"
}

val appVersion: String = findProperty("appVersion")?.toString() ?: gitVersion()

allprojects {
    group = "ru.zinin"
    version = appVersion

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
    apply(plugin = "com.gorylenko.gradle-git-properties")

    configurations.all {
        resolutionStrategy.cacheChangingModulesFor(0, "seconds")
    }

    java {
        toolchain {
            languageVersion = JavaLanguageVersion.of(25)
        }
    }

    kotlin {
        compilerOptions {
            freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
        }
    }

    ktlint {
        version.set(libs.versions.ktlint.tool.get())

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
        toolVersion = libs.versions.jacoco.get()
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
        jvmArgs(
            "--add-opens",
            "java.base/java.nio.file=ALL-UNNAMED",
            "--add-opens",
            "java.base/java.lang=ALL-UNNAMED",
            "--add-opens",
            "java.base/java.lang.reflect=ALL-UNNAMED",
            "--add-opens",
            "java.base/java.util=ALL-UNNAMED",
        )
    }

    tasks.withType<JavaCompile> {
        options.compilerArgs.addAll(listOf("-Xlint:unchecked", "-Xlint:deprecation"))
    }
}
