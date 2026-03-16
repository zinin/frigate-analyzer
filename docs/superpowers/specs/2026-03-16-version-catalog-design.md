# Миграция на Gradle Version Catalog (libs.versions.toml)

## Цель

Перенести управление версиями зависимостей и плагинов из `extra[...]` / `plugins {}` в корневом `build.gradle.kts` в стандартный механизм Gradle Version Catalog (`gradle/libs.versions.toml`).

## Мотивация

- `libs.versions.toml` — рекомендуемый Gradle подход (начиная с 7.x) для централизации версий
- IDE автодополнение для `libs.*` accessor-ов
- Type-safe обращение к зависимостям (ошибки на этапе компиляции, а не в runtime)
- Единый файл для всех версий вместо `extra[...]` блока

## Scope

- Создать `gradle/libs.versions.toml` с секциями `[versions]`, `[libraries]`, `[plugins]`, `[bundles]`
- Обновить корневой `build.gradle.kts`: заменить `plugins {}` на `alias(...)`, удалить `extra[...]`
- Обновить все 5 модулей: заменить `${property("...")}` на `libs.*`
- Удалить неиспользуемые версии (`commonsLang3Version`, `jakartaMailApiVersion`)
- Исправить дубль jackson-зависимостей в core

## Что НЕ меняется

- `io.spring.dependency-management` плагин остаётся — он управляет версиями Spring-экосистемы
- `apply(plugin = ...)` в блоке `subprojects {}` остаётся как есть — apply по строке не поддерживает alias
- Встроенные плагины (`maven-publish`, `jacoco`) остаются через `id(...)`
- Блоки `plugins {}` в модулях остаются через `id(...)` без версий — версии уже разрешены корневым `plugins {}` блоком (например, `id("org.springframework.boot")` в core, `kotlin("kapt")` в service)
- Блоки `configurations {}` в модулях (в т.ч. `create("liquibase")` и `exclude` в core)
- Блок `kapt {}` с аргументами MapStruct в service
- Блок `springBoot { buildInfo() }` в core
- Конфигурации `tasks.bootJar`, `tasks.jar` во всех модулях
- Liquibase-задачи (`liquibaseUpdate`, `liquibaseStatus`, `liquibaseValidate`) и их property-параметры в core
- Вся логика `subprojects {}`, `allprojects {}`, `gitVersion()` в корневом `build.gradle.kts`

---

## Дизайн

### 1. `gradle/libs.versions.toml`

#### `[versions]`

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
```

Удалены неиспользуемые: `commonsLang3Version`, `jakartaMailApiVersion`.

#### `[plugins]`

```toml
[plugins]
kotlin-jvm = { id = "org.jetbrains.kotlin.jvm", version.ref = "kotlin" }
kotlin-spring = { id = "org.jetbrains.kotlin.plugin.spring", version.ref = "kotlin" }
kotlin-kapt = { id = "org.jetbrains.kotlin.kapt", version.ref = "kotlin" }
spring-boot = { id = "org.springframework.boot", version.ref = "spring-boot" }
spring-dependency-management = { id = "io.spring.dependency-management", version.ref = "spring-dependency-management" }
ktlint = { id = "org.jlleitschuh.gradle.ktlint", version.ref = "ktlint-gradle" }
git-properties = { id = "com.gorylenko.gradle-git-properties", version.ref = "git-properties" }
```

Встроенные плагины (`maven-publish`, `jacoco`) не включены — у них нет внешних версий.

#### `[libraries]`

```toml
[libraries]
# Kotlin & Coroutines
kotlin-reflect = { module = "org.jetbrains.kotlin:kotlin-reflect" }
coroutines-core = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-core" }
coroutines-reactor = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-reactor" }
coroutines-test = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-test" }
kotlin-test-junit5 = { module = "org.jetbrains.kotlin:kotlin-test-junit5" }

# Spring (версии через Spring BOM)
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

# Jackson (версии через Spring BOM)
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
```

#### `[bundles]`

```toml
[bundles]
jackson = ["jackson-databind", "jackson-jsr310", "jackson-kotlin", "jackson-yaml"]
coroutines = ["coroutines-core", "coroutines-reactor"]
mapstruct = ["mapstruct", "mapstruct-spring-extensions"]
testcontainers = ["testcontainers-junit-jupiter", "testcontainers-postgresql"]
```

### 2. Корневой `build.gradle.kts`

**Plugins блок:**
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

**Удаляется:** весь блок `extra[...]` (строки 159–175).

**Изменения в `subprojects {}`:**
- Удалить дубль `apply(plugin = "org.jlleitschuh.gradle.ktlint")` (строка 66)
- `ktlint { version.set("1.8.0") }` → `ktlint { version.set(libs.versions.ktlint.tool.get()) }`
- `jacoco { toolVersion = "0.8.14" }` → `jacoco { toolVersion = libs.versions.jacoco.get() }`

**Без изменений:** `allprojects {}`, `gitVersion()`.

### 3. Изменения в модулях

#### common/build.gradle.kts

```kotlin
dependencies {
    implementation(libs.spring.boot.starter)
    implementation(libs.uuid.creator)
    implementation(libs.kotlin.logging)
}
```

#### model/build.gradle.kts

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

#### service/build.gradle.kts

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

#### telegram/build.gradle.kts

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

#### core/build.gradle.kts

```kotlin
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
```

Дубль jackson-зависимостей (строки 61–64 текущего файла) удалён.

### 4. Побочные исправления

- Удалены неиспользуемые версии: `commonsLang3Version` ("3.20.0"), `jakartaMailApiVersion` ("2.1.5")
- Удалён дубль jackson-зависимостей в core/build.gradle.kts
- Удалён дубль `apply(plugin = "org.jlleitschuh.gradle.ktlint")` в корневом `build.gradle.kts`
- Обновлена версия Spring Boot в CLAUDE.md: 4.0.2 → 4.0.3
