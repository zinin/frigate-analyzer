package ru.zinin.frigate.analyzer.core.config.transaction

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.junit.jupiter.SpringExtension
import ru.zinin.frigate.analyzer.core.IntegrationTestBase
import ru.zinin.frigate.analyzer.core.testsupport.GatedDbProxy
import ru.zinin.frigate.analyzer.core.testsupport.GatedDbProxy.Hold
import ru.zinin.frigate.analyzer.model.request.CreateRecordingRequest
import ru.zinin.frigate.analyzer.service.RecordingEntityService
import ru.zinin.frigate.analyzer.service.repository.AppSettingRepository
import java.sql.DriverManager
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID
import kotlin.test.assertFailsWith
import kotlin.test.fail
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

/**
 * Транзакция, открытие которой отменили посреди `BEGIN`, откатывается и не достаётся следующему, кто берёт соединение из
 * пула (spring-framework#36344, `docs/r2dbc-cancelled-transaction-leak.md`).
 *
 * Прокси держит ответ на `BEGIN`, пока тест отменяет вызов, а по серверу видно, что транзакция там открыта: окно отмены
 * поймано.
 */
@ExtendWith(SpringExtension::class)
class CancelledTransactionLeakTest : IntegrationTestBase() {
    @Autowired
    private lateinit var recordings: RecordingEntityService

    @Autowired
    private lateinit var settings: AppSettingRepository

    @Autowired
    private lateinit var databaseClient: DatabaseClient

    @Value("\${spring.r2dbc.username}")
    private lateinit var username: String

    @Value("\${spring.r2dbc.password}")
    private lateinit var password: String

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Занимает все соединения сразу, чтобы пул создал их до теста. */
    @BeforeEach
    fun fillPool() {
        runBlocking { onEveryConnection { databaseClient.sql("SELECT pg_sleep(0.1)").then().awaitSingleOrNull() } }
    }

    @AfterEach
    fun tearDown() {
        proxy.hold(Hold.NOTHING)
        scope.cancel()
        // Без защиты закрывает брошенную транзакцию, чтобы падение одного теста не роняло соседние.
        runBlocking { onEveryConnection { runCatching { recordings.incrementProcessAttempts(UUID.randomUUID()) } } }
    }

    @Test
    fun `cancelled transaction is rolled back as soon as its BEGIN is answered`() {
        runBlocking {
            cancelWhileBeginIsInFlight("BEGIN READ ONLY") { recordings.findPreviousSegment(CAMERA, FROM, BEFORE) }

            proxy.hold(Hold.NOTHING)

            await("the abandoned transaction must be rolled back") { transactionsOnServer().isEmpty() }
        }
    }

    @Test
    fun `body of a transaction whose BEGIN was cancelled does not run`() {
        runBlocking {
            val request = recordingRequest()
            cancelWhileBeginIsInFlight("BEGIN READ WRITE") { recordings.createRecording(request) }

            proxy.hold(Hold.NOTHING)
            await("the abandoned transaction must be rolled back") { transactionsOnServer().isEmpty() }

            assertThat(outside("SELECT id FROM recordings WHERE file_path = '${request.filePath}'")).isEmpty()
        }
    }

    /** Драйвер ещё не знает об открытой транзакции: ответ на `BEGIN` в пути. */
    @Test
    fun `connection taken while the cancelled BEGIN is in flight is outside its transaction`() {
        runBlocking {
            cancelWhileBeginIsInFlight("BEGIN READ ONLY") { recordings.findPreviousSegment(CAMERA, FROM, BEFORE) }

            val insideTransaction = scope.async { insideTransaction() }
            delay(ANSWER_TRAVEL_TIME)
            proxy.hold(Hold.NOTHING)

            assertThat(insideTransaction.await()).isFalse()
        }
    }

    /** Форма вызова судьи: чтение под `withTimeout`, а база ответить не успела. */
    @Test
    fun `write after a read cut off by a timeout succeeds`() {
        runBlocking {
            proxy.hold(Hold.BEGIN_ANSWERS)
            assertFailsWith<TimeoutCancellationException> {
                withTimeout(1.seconds) { recordings.findNextSegment(CAMERA, FROM, BEFORE) }
            }
            assertThat(transactionsOnServer()).containsExactly("BEGIN READ ONLY")

            proxy.hold(Hold.NOTHING)
            delay(ANSWER_TRAVEL_TIME)

            assertThat(recordings.createRecording(recordingRequest())).isNotNull()
        }
    }

    @Test
    fun `autocommit write after a cancelled write transaction is visible to other connections`() {
        runBlocking {
            val key = "test.${UUID.randomUUID()}"
            cancelWhileBeginIsInFlight("BEGIN READ WRITE") { recordings.incrementProcessAttempts(UUID.randomUUID()) }

            proxy.hold(Hold.NOTHING)
            delay(ANSWER_TRAVEL_TIME)
            settings.upsert(key, "1", Instant.now(), "test")

            assertThat(outside("SELECT setting_value FROM app_settings WHERE setting_key = '$key'")).containsExactly("1")
        }
    }

    /**
     * Защита не должна превращать зависание базы в отказы. Выдача соединения ограничена по времени (две попытки по
     * `max-acquire-time`), поэтому обмен с сервером при выдаче ронял бы такие вызовы, хотя соединение в пуле есть.
     */
    @Test
    fun `call started while the database still stalls after a cut-off one waits for it`() {
        runBlocking {
            proxy.hold(Hold.ALL_ANSWERS)
            assertFailsWith<TimeoutCancellationException> {
                withTimeout(300.milliseconds) { recordings.findNextSegment(CAMERA, FROM, BEFORE) }
            }

            val write = scope.async { recordings.createRecording(recordingRequest()) }
            delay(MAX_ACQUIRE_TIME * 2.5)
            proxy.hold(Hold.NOTHING)

            assertThat(write.await()).isNotNull()
        }
    }

    /** Запускает вызов и отменяет его, когда [begin] уже выполнен сервером, а ответ на него держит прокси. */
    private suspend fun cancelWhileBeginIsInFlight(
        begin: String,
        call: suspend () -> Unit,
    ) {
        proxy.hold(Hold.BEGIN_ANSWERS)
        val beginsBefore = proxy.beginsSent
        val job = scope.launch { call() }
        await("the call must send BEGIN") { proxy.beginsSent > beginsBefore }
        job.cancelAndJoin()
        await("the server must open the transaction") { transactionsOnServer() == listOf(begin) }
    }

    private suspend fun await(
        what: String,
        condition: () -> Boolean,
    ) {
        withTimeoutOrNull(AWAIT_LIMIT) {
            while (!condition()) delay(10.milliseconds)
        } ?: fail("$what; transactions on the server: ${transactionsOnServer()}")
    }

    private suspend fun onEveryConnection(call: suspend () -> Unit) {
        coroutineScope { repeat(POOL_SIZE) { launch { call() } } }
    }

    /** Простой запрос вне транзакции: в автокоммите обе метки времени совпадают. */
    private suspend fun insideTransaction(): Boolean =
        databaseClient
            .sql("SELECT statement_timestamp() <> transaction_timestamp()")
            .map { row -> row.get(0, Boolean::class.javaObjectType)!! }
            .one()
            .awaitSingle()

    private fun transactionsOnServer(): List<String> =
        outside("SELECT query FROM pg_stat_activity WHERE state = 'idle in transaction' AND usename = current_user")

    /** Запрос мимо пула приложения и мимо прокси. */
    private fun outside(sql: String): List<String> =
        DriverManager
            .getConnection(
                "jdbc:postgresql://${ENVIRONMENT_TEST.getServiceHost(SERVICE_DB, PORT_DB)}:" +
                    "${ENVIRONMENT_TEST.getServicePort(SERVICE_DB, PORT_DB)}/$DATABASE",
                username,
                password,
            ).use { connection ->
                connection.createStatement().executeQuery(sql).use { rows ->
                    buildList { while (rows.next()) add(rows.getString(1)) }
                }
            }

    private fun recordingRequest() =
        CreateRecordingRequest(
            filePath = "/recordings/${UUID.randomUUID()}.mp4",
            fileCreationTimestamp = FROM,
            camId = CAMERA,
            recordDate = LocalDate.of(2026, 11, 1),
            recordTime = LocalTime.MIDNIGHT,
            recordTimestamp = FROM,
        )

    companion object {
        private const val CAMERA = "cam1"
        private const val DATABASE = "frigate_analyzer"
        private const val POOL_SIZE = 2
        private val FROM = Instant.parse("2026-11-01T00:00:00Z")
        private val BEFORE = Instant.parse("2026-11-01T00:01:00Z")
        private val MAX_ACQUIRE_TIME = 1.seconds
        private val AWAIT_LIMIT = 5.seconds

        /** Запас, за который ответ доходит от прокси до драйвера. Короче нужного — тест мягче, но ложно не упадёт. */
        private val ANSWER_TRAVEL_TIME = 200.milliseconds

        private val proxy by lazy {
            GatedDbProxy(
                ENVIRONMENT_TEST.getServiceHost(SERVICE_DB, PORT_DB),
                ENVIRONMENT_TEST.getServicePort(SERVICE_DB, PORT_DB),
            )
        }

        @JvmStatic
        @DynamicPropertySource
        fun databaseThroughProxy(registry: DynamicPropertyRegistry) {
            registry.add("spring.r2dbc.url") { "r2dbc:postgresql://localhost:${proxy.port}/$DATABASE" }
            registry.add("spring.r2dbc.pool.initial-size") { POOL_SIZE }
            registry.add("spring.r2dbc.pool.max-size") { POOL_SIZE }
            registry.add("spring.r2dbc.pool.max-acquire-time") { MAX_ACQUIRE_TIME.toJavaDuration() }
        }
    }
}
