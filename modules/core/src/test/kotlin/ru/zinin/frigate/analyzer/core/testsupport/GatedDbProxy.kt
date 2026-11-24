package ru.zinin.frigate.analyzer.core.testsupport

import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * TCP-прокси до базы, который по команде теста придерживает её ответы: на `BEGIN`, чтобы тест попал в окно между
 * отправкой `BEGIN` и ответом на него (на локальной базе это доли миллисекунды), или все сразу, как у зависшей базы.
 * Запросы проходят как есть.
 *
 * Потоки виртуальные, то есть демоны, и закрывать прокси не нужно: контекст Spring закрывает свой пул уже после
 * методов `@AfterAll`.
 */
class GatedDbProxy(
    private val targetHost: String,
    private val targetPort: Int,
) {
    enum class Hold { NOTHING, BEGIN_ANSWERS, ALL_ANSWERS }

    private val serverSocket = ServerSocket(0, BACKLOG, InetAddress.getLoopbackAddress())
    private val begins = AtomicInteger()
    private val lock = ReentrantLock()
    private val holdChanged = lock.newCondition()
    private var hold = Hold.NOTHING

    val port: Int get() = serverSocket.localPort

    /** Сколько `BEGIN` ушло на сервер с запуска прокси. */
    val beginsSent: Int get() = begins.get()

    init {
        Thread.ofVirtual().start {
            while (true) {
                connect(serverSocket.accept())
            }
        }
    }

    fun hold(what: Hold) {
        lock.withLock {
            hold = what
            holdChanged.signalAll()
        }
    }

    private fun connect(client: Socket) {
        try {
            val server = Socket(targetHost, targetPort)
            client.tcpNoDelay = true
            server.tcpNoDelay = true
            val beginUnanswered = AtomicBoolean()
            Thread.ofVirtual().start {
                pump(client, server) { chunk, send ->
                    val begin = "BEGIN" in chunk
                    if (begin) beginUnanswered.set(true)
                    send()
                    if (begin) begins.incrementAndGet()
                }
            }
            Thread.ofVirtual().start {
                pump(server, client) { _, send ->
                    awaitPass(answersBegin = beginUnanswered.getAndSet(false))
                    send()
                }
            }
        } catch (_: IOException) {
            client.close()
        }
    }

    private fun awaitPass(answersBegin: Boolean) {
        lock.withLock {
            while (hold == Hold.ALL_ANSWERS || (hold == Hold.BEGIN_ANSWERS && answersBegin)) holdChanged.await()
        }
    }

    private fun pump(
        from: Socket,
        to: Socket,
        forward: (chunk: String, send: () -> Unit) -> Unit,
    ) {
        val buffer = ByteArray(BUFFER_SIZE)
        try {
            from.use {
                to.use {
                    while (true) {
                        val read = from.getInputStream().read(buffer)
                        if (read < 0) return
                        forward(String(buffer, 0, read, Charsets.ISO_8859_1)) { to.getOutputStream().write(buffer, 0, read) }
                    }
                }
            }
        } catch (_: IOException) {
            // Соединение закрыла одна из сторон.
        }
    }

    private companion object {
        const val BACKLOG = 200
        const val BUFFER_SIZE = 64 * 1024
    }
}
