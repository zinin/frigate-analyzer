package ru.zinin.frigate.analyzer.core.testsupport

import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * TCP-прокси до базы, который по команде теста придерживает все её ответы, как у зависшей базы. Так тест попадает в
 * окно между отправкой `BEGIN` и ответом на него: на локальной базе это доли миллисекунды. Запросы проходят как есть.
 *
 * Потоки виртуальные, то есть демоны, и закрывать прокси не нужно: контекст Spring закрывает свой пул уже после
 * методов `@AfterAll`.
 */
class GatedDbProxy(
    private val targetHost: String,
    private val targetPort: Int,
) {
    enum class Hold { NOTHING, ALL_ANSWERS }

    private val serverSocket = ServerSocket(0, BACKLOG, InetAddress.getLoopbackAddress())
    private val lock = ReentrantLock()
    private val holdChanged = lock.newCondition()
    private var hold = Hold.NOTHING

    val port: Int get() = serverSocket.localPort

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
            Thread.ofVirtual().start { pump(client, server) { } }
            Thread.ofVirtual().start { pump(server, client) { awaitPass() } }
        } catch (_: IOException) {
            client.close()
        }
    }

    private fun awaitPass() {
        lock.withLock {
            while (hold == Hold.ALL_ANSWERS) holdChanged.await()
        }
    }

    private fun pump(
        from: Socket,
        to: Socket,
        beforeSend: () -> Unit,
    ) {
        val buffer = ByteArray(BUFFER_SIZE)
        try {
            from.use {
                to.use {
                    while (true) {
                        val read = from.getInputStream().read(buffer)
                        if (read < 0) return
                        beforeSend()
                        to.getOutputStream().write(buffer, 0, read)
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
