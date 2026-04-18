package ru.zinin.frigate.analyzer.core.testsupport

import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.RecordedRequest
import okio.Buffer
import java.util.concurrent.atomic.AtomicInteger

class DetectServiceDispatcher : Dispatcher() {
    override fun dispatch(request: RecordedRequest): MockResponse {
        val path = request.url.encodedPath
        val method = request.method ?: "GET"

        return when (path) {
            "/" -> {
                respondJson(200, "{}")
            }

            "/health" -> {
                respondJson(200, "{}")
            }

            "/detect" -> {
                if (method != "POST") {
                    respondJson(405, errorJson("Method Not Allowed"))
                } else {
                    respondJson(200, detectResponseJson())
                }
            }

            "/extract/frames" -> {
                if (method != "POST") {
                    respondJson(405, errorJson("Method Not Allowed"))
                } else {
                    respondJson(200, frameExtractionResponseJson())
                }
            }

            "/detect/visualize" -> {
                if (method != "POST") {
                    respondJson(405, errorJson("Method Not Allowed"))
                } else {
                    respondBinary(200, "image/jpeg", fakeJpegBytes())
                }
            }

            "/detect/video/visualize" -> {
                if (method != "POST") {
                    respondJson(405, errorJson("Method Not Allowed"))
                } else {
                    respondJson(202, jobCreatedResponseJson())
                }
            }

            else -> {
                if (path.matches(Regex("/jobs/[^/]+/download"))) {
                    respondBinary(200, "video/mp4", fakeVideoBytes())
                } else if (path.matches(Regex("/jobs/[^/]+/cancel"))) {
                    if (method == "POST") {
                        respondJson(200, jobStatusCancelledJson())
                    } else {
                        respondJson(405, errorJson("Method Not Allowed"))
                    }
                } else if (path.matches(Regex("/jobs/[^/]+"))) {
                    respondJson(200, jobStatusCompletedJson())
                } else {
                    respondJson(404, errorJson("Not Found"))
                }
            }
        }
    }

    private fun respondJson(
        status: Int,
        body: String,
    ): MockResponse =
        MockResponse
            .Builder()
            .code(status)
            .addHeader("Content-Type", "application/json; charset=utf-8")
            .body(body)
            .build()

    private fun respondBinary(
        status: Int,
        contentType: String,
        bytes: ByteArray,
    ): MockResponse =
        MockResponse
            .Builder()
            .code(status)
            .addHeader("Content-Type", contentType)
            .body(Buffer().write(bytes))
            .build()

    private fun errorJson(message: String): String = """{"detail":[{"loc":["request"],"msg":"$message","type":"mock"}]}"""

    private fun detectResponseJson(): String =
        """
        {
          "detections": [
            {
              "class_id": 0,
              "class_name": "person",
              "confidence": 0.95,
              "bbox": {"x1": 100.0, "y1": 50.0, "x2": 300.0, "y2": 400.0}
            }
          ],
          "processing_time": 42,
          "image_size": {"width": 1920, "height": 1080},
          "model": "yolo26s.pt"
        }
        """.trimIndent()

    private fun frameExtractionResponseJson(): String =
        """
        {
          "success": true,
          "video_duration": 2.5,
          "video_resolution": [1920, 1080],
          "frames_extracted": 1,
          "frames": [
            {
              "frame_number": 1,
              "timestamp": 0.0,
              "image_base64": "ZmFrZV9qcGVn",
              "width": 1920,
              "height": 1080
            }
          ],
          "processing_time_ms": 80
        }
        """.trimIndent()

    private fun fakeJpegBytes(): ByteArray {
        // Minimal but valid JPEG: SOI + APP0 (JFIF) + EOI
        return byteArrayOf(
            0xFF.toByte(),
            0xD8.toByte(), // SOI (Start of Image)
            0xFF.toByte(),
            0xE0.toByte(), // APP0 marker
            0x00.toByte(),
            0x10.toByte(), // Length: 16 bytes
            0x4A.toByte(),
            0x46.toByte(),
            0x49.toByte(),
            0x46.toByte(),
            0x00.toByte(), // "JFIF\0"
            0x01.toByte(),
            0x01.toByte(), // Version 1.1
            0x00.toByte(), // Units (aspect ratio)
            0x01.toByte(),
            0x00.toByte(), // X density
            0x01.toByte(),
            0x00.toByte(), // Y density
            0x00.toByte(),
            0x00.toByte(), // Thumbnail dimensions (0x0)
            0xFF.toByte(),
            0xD9.toByte(), // EOI (End of Image)
        )
    }

    private fun jobCreatedResponseJson(): String =
        """
        {
          "job_id": "test-job-123",
          "status": "queued",
          "message": "Video annotation job created"
        }
        """.trimIndent()

    private fun jobStatusCompletedJson(): String =
        """
        {
          "job_id": "test-job-123",
          "status": "completed",
          "progress": 100,
          "created_at": "2026-02-16T12:00:00Z",
          "completed_at": "2026-02-16T12:05:00Z",
          "download_url": "/jobs/test-job-123/download",
          "error": null,
          "stats": {
            "total_frames": 300,
            "detected_frames": 50,
            "tracked_frames": 250,
            "total_detections": 120,
            "processing_time_ms": 15000
          }
        }
        """.trimIndent()

    private fun jobStatusCancelledJson(): String =
        """
        {
          "job_id": "test-job-123",
          "status": "cancelled",
          "progress": 50,
          "created_at": "2026-02-16T12:00:00Z",
          "completed_at": null,
          "download_url": null,
          "error": null,
          "stats": null
        }
        """.trimIndent()

    private fun fakeVideoBytes(): ByteArray =
        byteArrayOf(
            0x00.toByte(),
            0x00.toByte(),
            0x00.toByte(),
            0x1C.toByte(),
            0x66.toByte(),
            0x74.toByte(),
            0x79.toByte(),
            0x70.toByte(),
        )
}

/**
 * Configurable dispatcher for testing error scenarios.
 *
 * @param initialFailureCount Number of times to return errors before returning success
 * @param httpErrorCode HTTP code to return on failure (default: 500)
 */
class ConfigurableDetectServiceDispatcher(
    private val initialFailureCount: Int = 0,
    private val httpErrorCode: Int = 500,
) : Dispatcher() {
    private val failureCount = AtomicInteger(initialFailureCount)
    private val requestCount = AtomicInteger(0)

    override fun dispatch(request: RecordedRequest): MockResponse {
        val path = request.url.encodedPath
        val method = request.method ?: "GET"
        requestCount.incrementAndGet()

        // Check if we should return failure
        val failuresRemaining = failureCount.get()
        if (failuresRemaining > 0) {
            failureCount.decrementAndGet()
            return respondJson(httpErrorCode, errorJson("Simulated failure"))
        }

        return when (path) {
            "/" -> {
                respondJson(200, "{}")
            }

            "/health" -> {
                respondJson(200, "{}")
            }

            "/detect" -> {
                if (method != "POST") {
                    respondJson(405, errorJson("Method Not Allowed"))
                } else {
                    respondJson(200, detectResponseJson())
                }
            }

            "/extract/frames" -> {
                if (method != "POST") {
                    respondJson(405, errorJson("Method Not Allowed"))
                } else {
                    respondJson(200, frameExtractionResponseJson())
                }
            }

            "/detect/visualize" -> {
                if (method != "POST") {
                    respondJson(405, errorJson("Method Not Allowed"))
                } else {
                    respondBinary(200, "image/jpeg", fakeJpegBytes())
                }
            }

            "/detect/video/visualize" -> {
                if (method != "POST") {
                    respondJson(405, errorJson("Method Not Allowed"))
                } else {
                    respondJson(202, jobCreatedResponseJson())
                }
            }

            else -> {
                if (path.matches(Regex("/jobs/[^/]+/download"))) {
                    respondBinary(200, "video/mp4", fakeVideoBytes())
                } else if (path.matches(Regex("/jobs/[^/]+"))) {
                    respondJson(200, jobStatusCompletedJson())
                } else {
                    respondJson(404, errorJson("Not Found"))
                }
            }
        }
    }

    fun getRequestCount(): Int = requestCount.get()

    fun getFailuresRemaining(): Int = failureCount.get()

    private fun respondJson(
        status: Int,
        body: String,
    ): MockResponse =
        MockResponse
            .Builder()
            .code(status)
            .addHeader("Content-Type", "application/json; charset=utf-8")
            .body(body)
            .build()

    private fun respondBinary(
        status: Int,
        contentType: String,
        bytes: ByteArray,
    ): MockResponse =
        MockResponse
            .Builder()
            .code(status)
            .addHeader("Content-Type", contentType)
            .body(Buffer().write(bytes))
            .build()

    private fun errorJson(message: String): String = """{"detail":[{"loc":["request"],"msg":"$message","type":"mock"}]}"""

    private fun detectResponseJson(): String =
        """
        {
          "detections": [
            {
              "class_id": 0,
              "class_name": "person",
              "confidence": 0.95,
              "bbox": {"x1": 100.0, "y1": 50.0, "x2": 300.0, "y2": 400.0}
            }
          ],
          "processing_time": 42,
          "image_size": {"width": 1920, "height": 1080},
          "model": "yolo26s.pt"
        }
        """.trimIndent()

    private fun frameExtractionResponseJson(): String =
        """
        {
          "success": true,
          "video_duration": 2.5,
          "video_resolution": [1920, 1080],
          "frames_extracted": 1,
          "frames": [
            {
              "frame_number": 1,
              "timestamp": 0.0,
              "image_base64": "ZmFrZV9qcGVn",
              "width": 1920,
              "height": 1080
            }
          ],
          "processing_time_ms": 80
        }
        """.trimIndent()

    private fun fakeJpegBytes(): ByteArray {
        // Minimal but valid JPEG: SOI + APP0 (JFIF) + EOI
        return byteArrayOf(
            0xFF.toByte(),
            0xD8.toByte(), // SOI (Start of Image)
            0xFF.toByte(),
            0xE0.toByte(), // APP0 marker
            0x00.toByte(),
            0x10.toByte(), // Length: 16 bytes
            0x4A.toByte(),
            0x46.toByte(),
            0x49.toByte(),
            0x46.toByte(),
            0x00.toByte(), // "JFIF\0"
            0x01.toByte(),
            0x01.toByte(), // Version 1.1
            0x00.toByte(), // Units (aspect ratio)
            0x01.toByte(),
            0x00.toByte(), // X density
            0x01.toByte(),
            0x00.toByte(), // Y density
            0x00.toByte(),
            0x00.toByte(), // Thumbnail dimensions (0x0)
            0xFF.toByte(),
            0xD9.toByte(), // EOI (End of Image)
        )
    }

    private fun jobCreatedResponseJson(): String =
        """
        {
          "job_id": "test-job-123",
          "status": "queued",
          "message": "Video annotation job created"
        }
        """.trimIndent()

    private fun jobStatusCompletedJson(): String =
        """
        {
          "job_id": "test-job-123",
          "status": "completed",
          "progress": 100,
          "created_at": "2026-02-16T12:00:00Z",
          "completed_at": "2026-02-16T12:05:00Z",
          "download_url": "/jobs/test-job-123/download",
          "error": null,
          "stats": {
            "total_frames": 300,
            "detected_frames": 50,
            "tracked_frames": 250,
            "total_detections": 120,
            "processing_time_ms": 15000
          }
        }
        """.trimIndent()

    private fun fakeVideoBytes(): ByteArray =
        byteArrayOf(
            0x00.toByte(),
            0x00.toByte(),
            0x00.toByte(),
            0x1C.toByte(),
            0x66.toByte(),
            0x74.toByte(),
            0x79.toByte(),
            0x70.toByte(),
        )
}

/**
 * Dispatcher that returns a failed job status for testing error scenarios.
 */
class JobFailedDispatcher : Dispatcher() {
    override fun dispatch(request: RecordedRequest): MockResponse {
        val path = request.url.encodedPath

        return when {
            path == "/detect/video/visualize" -> {
                respondJson(202, """{"job_id":"fail-job","status":"queued","message":"Created"}""")
            }

            path.matches(Regex("/jobs/[^/]+")) && !path.contains("/download") -> {
                respondJson(
                    200,
                    """{"job_id":"fail-job","status":"failed","progress":0,"created_at":"2026-02-16T12:00:00Z","error":"Out of GPU memory"}""",
                )
            }

            else -> {
                respondJson(404, """{"detail":"Not Found"}""")
            }
        }
    }

    private fun respondJson(
        status: Int,
        body: String,
    ): MockResponse =
        MockResponse
            .Builder()
            .code(status)
            .addHeader("Content-Type", "application/json; charset=utf-8")
            .body(body)
            .build()
}

/**
 * Dispatcher that always returns "processing" status, never completing.
 * Used for testing timeout scenarios.
 */
class NeverCompletingDispatcher : Dispatcher() {
    override fun dispatch(request: RecordedRequest): MockResponse {
        val path = request.url.encodedPath

        return when {
            path == "/detect/video/visualize" -> {
                respondJson(202, """{"job_id":"slow-job","status":"queued","message":"Created"}""")
            }

            path.matches(Regex("/jobs/[^/]+")) && !path.contains("/download") -> {
                respondJson(
                    200,
                    """{"job_id":"slow-job","status":"processing","progress":10,"created_at":"2026-02-16T12:00:00Z"}""",
                )
            }

            else -> {
                respondJson(404, """{"detail":"Not Found"}""")
            }
        }
    }

    private fun respondJson(
        status: Int,
        body: String,
    ): MockResponse =
        MockResponse
            .Builder()
            .code(status)
            .addHeader("Content-Type", "application/json; charset=utf-8")
            .body(body)
            .build()
}

/**
 * Dispatcher that fails the first N POST requests to /detect/video/visualize with a 500 error,
 * then succeeds normally (submit + poll completed + download).
 */
class TransientSubmitFailureDispatcher(
    private val failCount: Int = 1,
) : Dispatcher() {
    private val submitAttempts = AtomicInteger(0)

    override fun dispatch(request: RecordedRequest): MockResponse {
        val path = request.url.encodedPath

        return when {
            path == "/detect/video/visualize" -> {
                val attempt = submitAttempts.incrementAndGet()
                if (attempt <= failCount) {
                    respondJson(500, """{"detail":"Internal Server Error"}""")
                } else {
                    respondJson(202, """{"job_id":"retry-job","status":"queued","message":"Created"}""")
                }
            }

            path.matches(Regex("/jobs/[^/]+/download")) -> {
                respondBinary(200, "video/mp4", fakeVideoBytes())
            }

            path.matches(Regex("/jobs/[^/]+")) -> {
                respondJson(
                    200,
                    """{"job_id":"retry-job","status":"completed","progress":100,"created_at":"2026-02-16T12:00:00Z","completed_at":"2026-02-16T12:05:00Z","download_url":"/jobs/retry-job/download","error":null,"stats":{"total_frames":100,"detected_frames":20,"tracked_frames":80,"total_detections":50,"processing_time_ms":5000}}""",
                )
            }

            else -> {
                respondJson(404, """{"detail":"Not Found"}""")
            }
        }
    }

    fun getSubmitAttempts(): Int = submitAttempts.get()

    private fun respondJson(
        status: Int,
        body: String,
    ): MockResponse =
        MockResponse
            .Builder()
            .code(status)
            .addHeader("Content-Type", "application/json; charset=utf-8")
            .body(body)
            .build()

    private fun respondBinary(
        status: Int,
        contentType: String,
        bytes: ByteArray,
    ): MockResponse =
        MockResponse
            .Builder()
            .code(status)
            .addHeader("Content-Type", contentType)
            .body(Buffer().write(bytes))
            .build()

    private fun fakeVideoBytes(): ByteArray =
        byteArrayOf(
            0x00.toByte(),
            0x00.toByte(),
            0x00.toByte(),
            0x1C.toByte(),
            0x66.toByte(),
            0x74.toByte(),
            0x79.toByte(),
            0x70.toByte(),
        )
}
