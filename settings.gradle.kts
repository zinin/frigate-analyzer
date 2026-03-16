pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

rootProject.name = "frigate-analyzer"

val modules = listOf("common", "model", "service", "core", "telegram")

include(modules)

modules.forEach {
    val p = findProject(":$it")
    p!!.name = "frigate-analyzer-$it"
    p.projectDir = file("modules/$it")
}
