# Version Catalog Migration Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Migrate all dependency and plugin versions from `extra[...]` / `plugins {}` in root `build.gradle.kts` to Gradle Version Catalog (`gradle/libs.versions.toml`).

**Architecture:** Create `libs.versions.toml` with all versions, libraries, plugins, and bundles. Update root and module build files to use `libs.*` accessors. Spring dependency management plugin stays for BOM-managed versions.

**Tech Stack:** Gradle Version Catalog, Kotlin DSL

**Spec:** `docs/superpowers/specs/2026-03-16-version-catalog-design.md`

---

## Chunk 1: Create Version Catalog and update root build

### Task 1: Create `gradle/libs.versions.toml`

**Files:**
- Create: `gradle/libs.versions.toml`

- [ ] **Step 1: Create the version catalog file**

```toml
[versions]
kotlin = "2.3.10"
spring-boot = "4.0.3"
spring-dependency-management = "1.1.7"
ktlint-gradle = "14.0.1"
git-properties = "2.5.7"

kotlin-logging = "8.0.01"
postgresql = "42.7.10"
mapstruct = "1.6.3"
mapstruct-spring-extensions = "2.0.0"
jakarta-inject-api = "2.0.1"
uuid-creator = "6.1.1"
testcontainers = "2.0.3"
openapi-starter = "3.0.2"
ktgbotapi = "31.2.0"
ktor = "3.4.1"
mockwebserver = "5.3.2"
mockk = "1.14.9"
liquibase = "5.0.1"
picocli = "4.7.7"
ktlint-tool = "1.8.0"
jacoco = "0.8.14"

[libraries]
# Kotlin & Coroutines
kotlin-reflect = { module = "org.jetbrains.kotlin:kotlin-reflect" }
coroutines-core = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-core" }
coroutines-reactor = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-reactor" }
coroutines-test = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-test" }
kotlin-test-junit5 = { module = "org.jetbrains.kotlin:kotlin-test-junit5" }

# Spring (versions managed by Spring BOM)
spring-boot-starter = { module = "org.springframework.boot:spring-boot-starter" }
spring-boot-starter-actuator = { module = "org.springframework.boot:spring-boot-starter-actuator" }
spring-boot-starter-validation = { module = "org.springframework.boot:spring-boot-starter-validation" }
spring-boot-starter-webflux = { module = "org.springframework.boot:spring-boot-starter-webflux" }
spring-boot-starter-data-r2dbc = { module = "org.springframework.boot:spring-boot-starter-data-r2dbc" }
spring-boot-starter-log4j2 = { module = "org.springframework.boot:spring-boot-starter-log4j2" }
spring-boot-starter-test = { module = "org.springframework.boot:spring-boot-starter-test" }
spring-boot-starter-webclient-test = { module = "org.springframework.boot:spring-boot-starter-webclient-test" }
spring-boot-starter-webflux-test = { module = "org.springframework.boot:spring-boot-starter-webflux-test" }
spring-data-r2dbc = { module = "org.springframework.data:spring-data-r2dbc" }

# Jackson (versions managed by Spring BOM)
jackson-databind = { module = "com.fasterxml.jackson.core:jackson-databind" }
jackson-annotations = { module = "com.fasterxml.jackson.core:jackson-annotations" }
jackson-jsr310 = { module = "com.fasterxml.jackson.datatype:jackson-datatype-jsr310" }
jackson-kotlin = { module = "com.fasterxml.jackson.module:jackson-module-kotlin" }
jackson-yaml = { module = "com.fasterxml.jackson.dataformat:jackson-dataformat-yaml" }

# Reactor
reactor-kotlin-extensions = { module = "io.projectreactor.kotlin:reactor-kotlin-extensions" }

# Database
postgresql = { module = "org.postgresql:postgresql", version.ref = "postgresql" }
r2dbc-postgresql = { module = "org.postgresql:r2dbc-postgresql" }

# Logging
kotlin-logging = { module = "io.github.oshai:kotlin-logging-jvm", version.ref = "kotlin-logging" }

# MapStruct
mapstruct = { module = "org.mapstruct:mapstruct", version.ref = "mapstruct" }
mapstruct-processor = { module = "org.mapstruct:mapstruct-processor", version.ref = "mapstruct" }
mapstruct-spring-extensions = { module = "org.mapstruct.extensions.spring:mapstruct-spring-extensions", version.ref = "mapstruct-spring-extensions" }
jakarta-inject-api = { module = "jakarta.inject:jakarta.inject-api", version.ref = "jakarta-inject-api" }

# UUID
uuid-creator = { module = "com.github.f4b6a3:uuid-creator", version.ref = "uuid-creator" }

# OpenAPI
openapi-starter-webflux-ui = { module = "org.springdoc:springdoc-openapi-starter-webflux-ui", version.ref = "openapi-starter" }
openapi-starter-common = { module = "org.springdoc:springdoc-openapi-starter-common", version.ref = "openapi-starter" }

# Telegram
ktgbotapi = { module = "dev.inmo:tgbotapi", version.ref = "ktgbotapi" }
ktor-client-okhttp = { module = "io.ktor:ktor-client-okhttp", version.ref = "ktor" }

# Testing
testcontainers-bom = { module = "org.testcontainers:testcontainers-bom", version.ref = "testcontainers" }
testcontainers-junit-jupiter = { module = "org.testcontainers:testcontainers-junit-jupiter" }
testcontainers-postgresql = { module = "org.testcontainers:testcontainers-postgresql" }
mockwebserver = { module = "com.squareup.okhttp3:mockwebserver3", version.ref = "mockwebserver" }
mockk = { module = "io.mockk:mockk", version.ref = "mockk" }
junit-platform-launcher = { module = "org.junit.platform:junit-platform-launcher" }

# Liquibase
liquibase-core = { module = "org.liquibase:liquibase-core", version.ref = "liquibase" }
picocli = { module = "info.picocli:picocli", version.ref = "picocli" }

[bundles]
jackson = ["jackson-databind", "jackson-jsr310", "jackson-kotlin", "jackson-yaml"]
coroutines = ["coroutines-core", "coroutines-reactor"]
mapstruct = ["mapstruct", "mapstruct-spring-extensions"]
testcontainers = ["testcontainers-junit-jupiter", "testcontainers-postgresql"]

[plugins]
kotlin-jvm = { id = "org.jetbrains.kotlin.jvm", version.ref = "kotlin" }
kotlin-spring = { id = "org.jetbrains.kotlin.plugin.spring", version.ref = "kotlin" }
kotlin-kapt = { id = "org.jetbrains.kotlin.kapt", version.ref = "kotlin" }
spring-boot = { id = "org.springframework.boot", version.ref = "spring-boot" }
spring-dependency-management = { id = "io.spring.dependency-management", version.ref = "spring-dependency-management" }
ktlint = { id = "org.jlleitschuh.gradle.ktlint", version.ref = "ktlint-gradle" }
git-properties = { id = "com.gorylenko.gradle-git-properties", version.ref = "git-properties" }
```

- [ ] **Step 2: Commit**

```bash
git add gradle/libs.versions.toml
git commit -m "build: add Gradle Version Catalog (libs.versions.toml)"
```

### Task 2: Update root `build.gradle.kts`

**Files:**
- Modify: `build.gradle.kts`

Four changes: (a) replace `plugins {}` block with `alias(...)`, (b) delete the `extra[...]` block, (c) remove duplicate ktlint apply in `subprojects {}`, (d) reference ktlint-tool and jacoco versions from catalog.

- [ ] **Step 1: Replace plugins block**

Replace lines 1-11:

```kotlin
plugins {
    kotlin("jvm") version "2.3.10"
    kotlin("plugin.spring") version "2.3.10"
    id("org.springframework.boot") version "4.0.3" apply false
    id("io.spring.dependency-management") version "1.1.7" apply false
    id("maven-publish")
    id("org.jlleitschuh.gradle.ktlint") version "14.0.1"
    id("jacoco")
    id("com.gorylenko.gradle-git-properties") version "2.5.7" apply false
    kotlin("kapt") version "2.3.10" apply false
}
```

With:

```kotlin
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
```

- [ ] **Step 2: Delete `extra[...]` block**

Delete lines 159-175 (the entire `extra[...]` block):

```kotlin
extra["kotlinLoggingVersion"] = "8.0.01"
extra["postgresqlVersion"] = "42.7.10"
extra["mapstructVersion"] = "1.6.3"
extra["mapstructSpringExtensionsVersion"] = "2.0.0"
extra["jakartaInjectApiVersion"] = "2.0.1"
extra["jakartaMailApiVersion"] = "2.1.5"
extra["uuidCreatorVersion"] = "6.1.1"
extra["testcontainersVersion"] = "2.0.3"
extra["openApiStarterVersion"] = "3.0.2"
extra["ktgbotapiVersion"] = "31.2.0"
extra["ktorVersion"] = "3.4.1"
extra["mockwebserverVersion"] = "5.3.2"
extra["mockkVersion"] = "1.14.9"
extra["liquibaseCoreVersion"] = "5.0.1"
extra["picocliVersion"] = "4.7.7"
extra["commonsLang3Version"] = "3.20.0"
```

- [ ] **Step 3: Remove duplicate ktlint apply in `subprojects {}`**

In `subprojects {}` block, remove the duplicate line 66:

```kotlin
// Remove this duplicate (line 66):
apply(plugin = "org.jlleitschuh.gradle.ktlint")
```

Line 64 `apply(plugin = "org.jlleitschuh.gradle.ktlint")` stays.

- [ ] **Step 4: Reference ktlint-tool and jacoco versions from catalog**

In `subprojects {}` block, replace:

```kotlin
ktlint {
    version.set("1.8.0")
```

With:

```kotlin
ktlint {
    version.set(libs.versions.ktlint.tool.get())
```

And replace:

```kotlin
jacoco {
    toolVersion = "0.8.14"
}
```

With:

```kotlin
jacoco {
    toolVersion = libs.versions.jacoco.get()
}
```

- [ ] **Step 5: Update CLAUDE.md**

Replace `Spring Boot 4.0.2` with `Spring Boot 4.0.3` in the Stack line.

- [ ] **Step 6: Commit**

```bash
git add build.gradle.kts CLAUDE.md
git commit -m "build: migrate root build.gradle.kts to Version Catalog"
```

## Chunk 2: Update module build files

### Task 3: Update `modules/common/build.gradle.kts`

**Files:**
- Modify: `modules/common/build.gradle.kts`

- [ ] **Step 1: Replace dependencies block**

Replace:

```kotlin
dependencies {
    implementation("org.springframework.boot:spring-boot-starter")
    implementation("com.github.f4b6a3:uuid-creator:${property("uuidCreatorVersion")}")

    implementation("io.github.oshai:kotlin-logging-jvm:${property("kotlinLoggingVersion")}")
}
```

With:

```kotlin
dependencies {
    implementation(libs.spring.boot.starter)
    implementation(libs.uuid.creator)
    implementation(libs.kotlin.logging)
}
```

- [ ] **Step 2: Commit**

```bash
git add modules/common/build.gradle.kts
git commit -m "build(common): migrate to Version Catalog"
```

### Task 4: Update `modules/model/build.gradle.kts`

**Files:**
- Modify: `modules/model/build.gradle.kts`

- [ ] **Step 1: Replace dependencies block**

Replace:

```kotlin
dependencies {
    implementation(project(":frigate-analyzer-common"))

    implementation("org.springframework.boot:spring-boot-starter")
    implementation("com.fasterxml.jackson.core:jackson-annotations")
    implementation("org.springframework.data:spring-data-r2dbc")

    implementation("com.github.f4b6a3:uuid-creator:${property("uuidCreatorVersion")}")

    implementation("io.github.oshai:kotlin-logging-jvm:${property("kotlinLoggingVersion")}")
}
```

With:

```kotlin
dependencies {
    implementation(project(":frigate-analyzer-common"))

    implementation(libs.spring.boot.starter)
    implementation(libs.jackson.annotations)
    implementation(libs.spring.data.r2dbc)
    implementation(libs.uuid.creator)
    implementation(libs.kotlin.logging)
}
```

- [ ] **Step 2: Commit**

```bash
git add modules/model/build.gradle.kts
git commit -m "build(model): migrate to Version Catalog"
```

### Task 5: Update `modules/service/build.gradle.kts`

**Files:**
- Modify: `modules/service/build.gradle.kts`

- [ ] **Step 1: Replace dependencies block**

Replace:

```kotlin
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
```

With:

```kotlin
dependencies {
    implementation(project(":frigate-analyzer-common"))
    api(project(":frigate-analyzer-model"))

    implementation(libs.spring.boot.starter)
    implementation(libs.spring.data.r2dbc)
    implementation(libs.uuid.creator)
    implementation(libs.kotlin.logging)
    implementation(libs.bundles.mapstruct)
    implementation(libs.jakarta.inject.api)

    kapt(libs.mapstruct.processor)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.kotlin.test.junit5)

    testRuntimeOnly(libs.junit.platform.launcher)
}
```

Blocks `plugins {}`, `tasks.bootJar`, and `kapt {}` remain unchanged.

- [ ] **Step 2: Commit**

```bash
git add modules/service/build.gradle.kts
git commit -m "build(service): migrate to Version Catalog"
```

### Task 6: Update `modules/telegram/build.gradle.kts`

**Files:**
- Modify: `modules/telegram/build.gradle.kts`

- [ ] **Step 1: Replace dependencies block**

Replace:

```kotlin
dependencies {
    implementation(project(":frigate-analyzer-common"))
    implementation(project(":frigate-analyzer-model"))

    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-data-r2dbc")

    // Telegram Bot API
    implementation("dev.inmo:tgbotapi:${property("ktgbotapiVersion")}")
    implementation("io.ktor:ktor-client-okhttp:${property("ktorVersion")}")

    // Logging
    implementation("io.github.oshai:kotlin-logging-jvm:${property("kotlinLoggingVersion")}")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor")

    // Test
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test")
    testImplementation("io.mockk:mockk:${property("mockkVersion")}")
}
```

With:

```kotlin
dependencies {
    implementation(project(":frigate-analyzer-common"))
    implementation(project(":frigate-analyzer-model"))

    implementation(libs.spring.boot.starter)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.boot.starter.data.r2dbc)
    implementation(libs.ktgbotapi)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.kotlin.logging)
    implementation(libs.bundles.coroutines)

    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.mockk)
}
```

- [ ] **Step 2: Commit**

```bash
git add modules/telegram/build.gradle.kts
git commit -m "build(telegram): migrate to Version Catalog"
```

### Task 7: Update `modules/core/build.gradle.kts`

**Files:**
- Modify: `modules/core/build.gradle.kts`

This is the largest change: replace `dependencyManagement` BOM reference, replace all dependencies, and remove the jackson duplicate (lines 61-64).

- [ ] **Step 1: Replace `dependencyManagement` block**

Replace:

```kotlin
dependencyManagement {
    imports {
        mavenBom("org.testcontainers:testcontainers-bom:${property("testcontainersVersion")}")
    }
}
```

With:

```kotlin
dependencyManagement {
    imports {
        mavenBom(libs.testcontainers.bom)
    }
}
```

- [ ] **Step 2: Replace `dependencies` block**

Replace the entire `dependencies { ... }` block (including removing the jackson duplicate) with:

```kotlin
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
```

Blocks `plugins {}`, `tasks.jar`, `springBoot {}`, `tasks.bootJar`, `configurations {}`, and all Liquibase tasks remain unchanged.

- [ ] **Step 3: Commit**

```bash
git add modules/core/build.gradle.kts
git commit -m "build(core): migrate to Version Catalog, remove jackson duplicate"
```

## Chunk 3: Verify build

### Task 8: Full build verification

- [ ] **Step 1: Run full build**

Run: `./gradlew build`

Expected: BUILD SUCCESSFUL. All modules compile, all tests pass.

If ktlint errors appear: run `./gradlew ktlintFormat`, then retry.

- [ ] **Step 2: Verify no `property(` references remain in build files**

Run: `grep -r 'property("' modules/*/build.gradle.kts build.gradle.kts`

Expected: No output (no remaining `property(...)` calls for version references).

- [ ] **Step 3: Verify no `extra[` references remain**

Run: `grep -r 'extra\[' build.gradle.kts`

Expected: No output.
