package ru.zinin.frigate.analyzer.ai.description.api

/**
 * Активный пресет судьи для потребителей за пределами модуля. Два метода, а не один: экран обязан
 * различать выбор владельца и то, что реально работает.
 */
interface ActiveJudgePreset {
    /** Что выбрал владелец; null = ключа нет или он пуст. Резолюции не делает. */
    suspend fun storedId(): String?

    /** Что применит следующий вызов judge: сохранённый, если годен, иначе fallback. */
    suspend fun effective(): DescriptionPreset
}
