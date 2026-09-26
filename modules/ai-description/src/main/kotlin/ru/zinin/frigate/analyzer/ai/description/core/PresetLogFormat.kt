package ru.zinin.frigate.analyzer.ai.description.core

import ru.zinin.frigate.analyzer.ai.description.api.DescriptionPreset

/**
 * Одна форма записи пресета в логе — `provider/model/effort`, — на стартовую строку каталога и на
 * строку об источнике активного пресета. Две копии разъехались бы, а оператор сверяет эти строки
 * между собой: одна перечисляет объявленное, вторая называет работающее.
 *
 * Модель именно эффективная: `ANTHROPIC_MODEL` вытесняет объявленную, и печатать объявленную
 * значило бы называть запрос, которого не будет. Расхождение объявленной и эффективной отдельно
 * называет WARN `DescriptionPresetCatalogBuilder.warnAboutDisplacedModels`.
 *
 * Пустой `effort` опускается, а не печатается пустым сегментом: у claude его не бывает вовсе.
 * Потолок кадра дописывается через запятую и только если задан: у большинства пресетов его нет.
 */
internal fun DescriptionPreset.logSignature(): String =
    listOfNotNull(provider, effectiveModel, effort.takeIf { it.isNotBlank() }).joinToString("/") +
        if (maxImageSide > 0) ", max-image-side=$maxImageSide" else ""
