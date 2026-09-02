package ru.zinin.frigate.analyzer.telegram.helper

import dev.inmo.tgbotapi.bot.exceptions.ApiException
import dev.inmo.tgbotapi.bot.exceptions.CommonBotException
import dev.inmo.tgbotapi.bot.exceptions.CommonRequestException
import dev.inmo.tgbotapi.bot.exceptions.TooMuchRequestsException
import dev.inmo.tgbotapi.types.Response
import dev.inmo.tgbotapi.types.RetryAfterError
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@OptIn(ExperimentalCoroutinesApi::class) // TestScope.currentTime
class RetryHelperTest {
    private fun errorAnswer(description: String = "Bad Request: RICH_MESSAGE_PHOTO_INVALID") =
        CommonRequestException(mockk<Response>(relaxed = true), description, null, null)

    private fun floodWait() =
        TooMuchRequestsException(
            mockk<RetryAfterError>(relaxed = true),
            mockk<Response>(relaxed = true),
            "Too Many Requests: retry after 5",
            null,
            null,
        )

    @Test
    fun `an error answer from Telegram is given up after three attempts`() =
        runTest {
            // 400 на битую разметку или 403 от заблокировавшего бота пользователя от повтора не
            // изменятся; три попытки лишь страхуют от редкого 5xx, пришедшего в виде ответа.
            var attempts = 0

            assertFailsWith<CommonRequestException> {
                RetryHelper.retryBounded("send", 42L) {
                    attempts++
                    throw errorAnswer()
                }
            }

            assertEquals(3, attempts)
            assertEquals(90_000L, currentTime, "30s + 60s between the three attempts, nothing after the last")
        }

    @Test
    fun `a failure without an answer is retried twenty times before giving up`() =
        runTest {
            // Сеть или таймаут проходят сами; полтора часа держат очередь через типичный сбой,
            // а тревога старше того всё равно бесполезна.
            var attempts = 0

            assertFailsWith<IOException> {
                RetryHelper.retryBounded("send", 42L) {
                    attempts++
                    throw IOException("connection reset")
                }
            }

            assertEquals(20, attempts)
            assertEquals(4_950_000L, currentTime, "30+60+120+240 s, then 15 × 300 s; no delay after the last attempt")
        }

    @Test
    fun `a flood-wait answer counts as no answer`() =
        runTest {
            // Штатно 429 повторяет лимитер библиотеки; если всплывёт, он не говорит, что запрос плох.
            var attempts = 0

            assertFailsWith<TooMuchRequestsException> {
                RetryHelper.retryBounded("send", 42L) {
                    attempts++
                    throw floodWait()
                }
            }

            assertEquals(20, attempts)
        }

    @Test
    fun `error answers are counted separately from failures without an answer`() =
        runTest {
            // Два отказа Telegram среди сетевых сбоев не должны сдаваться раньше третьего отказа.
            var attempts = 0

            assertFailsWith<CommonRequestException> {
                RetryHelper.retryBounded("send", 42L) {
                    attempts++
                    if (attempts % 2 == 0) throw errorAnswer() else throw IOException("timeout")
                }
            }

            assertEquals(6, attempts, "error answers on attempts 2, 4 and 6; the third one ends it")
        }

    @Test
    fun `a transport failure wrapped by the library counts as no answer`() =
        runTest {
            // Исполнитель библиотеки заворачивает сетевые сбои в CommonBotException; признак сети —
            // IOException в цепочке причин.
            var attempts = 0

            assertFailsWith<CommonBotException> {
                RetryHelper.retryBounded("send", 42L) {
                    attempts++
                    throw CommonBotException.Default("Something went wrong", IOException("connection reset"))
                }
            }

            assertEquals(20, attempts)
        }

    @Test
    fun `an answer the library could not parse counts as an error answer`() =
        runTest {
            // Тот же CommonBotException, но без сети в причинах: Telegram ответил, разбор упал.
            // Повтор отправил бы то же сообщение ещё раз и снова упал бы на разборе — дубликаты.
            var attempts = 0

            assertFailsWith<CommonBotException> {
                RetryHelper.retryBounded("send", 42L) {
                    attempts++
                    throw CommonBotException.Default(
                        "Something went wrong",
                        IllegalStateException("Unknown RichBlock type: button"),
                    )
                }
            }

            assertEquals(3, attempts)
        }

    @Test
    fun `an http error without a Bot API envelope counts as no answer`() =
        runTest {
            // 502 от шлюза приходит HTML-страницей, а не JSON-конвертом Bot API: это простой, не отказ.
            var attempts = 0

            assertFailsWith<ApiException> {
                RetryHelper.retryBounded("send", 42L) {
                    attempts++
                    throw ApiException(502, "<html>Bad Gateway</html>")
                }
            }

            assertEquals(20, attempts)
        }

    @Test
    fun `a failure inside the block itself is not retried twenty times`() =
        runTest {
            // Ошибка в нашем коде отправки повторится при каждой попытке.
            var attempts = 0

            assertFailsWith<IllegalStateException> {
                RetryHelper.retryBounded("send", 42L) {
                    attempts++
                    error("boom")
                }
            }

            assertEquals(3, attempts)
        }

    @Test
    fun `a success after failures returns the value`() =
        runTest {
            var attempts = 0

            val result =
                RetryHelper.retryBounded("send", 42L) {
                    attempts++
                    if (attempts < 3) throw IOException("blip")
                    "delivered"
                }

            assertEquals("delivered", result)
            assertEquals(3, attempts)
        }

    @Test
    fun `cancellation is never retried`() =
        runTest {
            var attempts = 0

            assertFailsWith<CancellationException> {
                RetryHelper.retryBounded("send", 42L) {
                    attempts++
                    throw CancellationException("shutdown")
                }
            }

            assertEquals(1, attempts)
        }
}
