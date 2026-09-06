package ru.zinin.frigate.analyzer.core.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver
import org.springframework.boot.context.properties.source.ConfigurationPropertySources
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider
import org.springframework.core.io.UrlResource
import org.springframework.core.type.filter.AnnotationTypeFilter
import ru.zinin.frigate.analyzer.ai.description.config.DescriptionProperties
import ru.zinin.frigate.analyzer.ai.description.config.JudgeProperties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Связывает секции упакованного `application.yaml` — того файла, который запускается на стенде.
 *
 * Больше его не читает никто: `src/test/resources/application.yaml` перекрывает его на classpath, а
 * локальный стенд подкладывает свой `application-local.yaml`. Секция, которая не связывается, поэтому
 * остаётся невидимой до падения контейнера на старте — так уехало `cameras: {}`: пустая map во
 * flow-нотации разворачивается в пустую строку, а конвертера из строки в `Map<String, CameraSection>`
 * нет.
 */
class PackagedApplicationYamlTest {
    private val binder: Binder by lazy {
        val sources = YamlPropertySourceLoader().load("packaged-application.yaml", packagedApplicationYaml())
        Binder(ConfigurationPropertySources.from(sources), PropertySourcesPlaceholdersResolver(sources))
    }

    @Test
    fun `judge section binds with no environment overrides`() {
        val properties = binder.bind("application.ai.judge", JudgeProperties::class.java).get()

        assertFalse(properties.enabled)
        assertEquals(emptyMap(), properties.cameras)
        assertEquals(200, properties.rateLimit.maxRequests)
    }

    @Test
    fun `description section binds with no environment overrides`() {
        val properties = binder.bind("application.ai.description", DescriptionProperties::class.java).get()

        assertFalse(properties.enabled)
        assertEquals("claude", properties.provider)
        assertTrue(properties.presets.isEmpty())
    }

    /**
     * Ловит тот же класс поломки в любой другой секции: тест ищет классы свойств сам, поэтому
     * новый `@ConfigurationProperties` попадает под проверку без правки теста.
     */
    @Test
    fun `every configuration properties class binds from the packaged config`() {
        val scanner = ClassPathScanningCandidateComponentProvider(false)
        scanner.addIncludeFilter(AnnotationTypeFilter(ConfigurationProperties::class.java))
        val classes =
            scanner.findCandidateComponents("ru.zinin.frigate.analyzer").map { Class.forName(it.beanClassName) }

        assertTrue(classes.size >= 10, "expected the scan to find the properties classes, found $classes")
        classes.forEach { type ->
            val annotation = type.getAnnotation(ConfigurationProperties::class.java)
            val prefix = annotation.prefix.ifEmpty { annotation.value }
            binder.bindOrCreate(prefix, type)
        }
    }

    /** Тот же `application.yaml` лежит и в тестовых ресурсах, поэтому берём не его. */
    private fun packagedApplicationYaml(): UrlResource {
        val onClasspath = javaClass.classLoader.getResources("application.yaml").toList()
        val packaged = onClasspath.filterNot { it.toString().contains("/resources/test/") }
        check(packaged.size == 1) { "expected one packaged application.yaml, found $onClasspath" }
        return UrlResource(packaged.single())
    }
}
