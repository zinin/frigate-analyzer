package ru.zinin.frigate.analyzer.ai.description.grok

/**
 * Строки `GROK_HOME/logs/unified.jsonl`, снятые с grok 1.0.41 (2026-09-25; `ctx` сокращён). После
 * смены `ARG GROK_VERSION` имя события и поля нужно сверить с живым запуском и обновить здесь.
 */
object GrokUnifiedLogFixtures {
    const val SID = "01a0d95a-363c-7b41-9de8-9bb19b368c9c"
    const val OTHER = "01a0d918-9e8f-7121-9c44-cfa258d0f169"

    fun stripped(
        sid: String,
        count: Int,
        reason: String = "payload_heuristic",
    ): String =
        """{"ts":"2026-09-25T16:16:07.051Z","src":"shell","pid":901027,"ver":"1.0.41","lvl":"warn","sid":"$sid",""" +
            """"msg":"shell.turn.images_stripped","ctx":{"sampler_request_id":"8363536a-4bd0-4e39-9387-e0a4fec96a0d",""" +
            """"stripped":$count,"reason":"$reason","persist_deferred":false}}"""

    fun inferenceDone(sid: String): String =
        """{"ts":"2026-09-25T15:04:35.889Z","src":"shell","pid":639093,"ver":"1.0.41","lvl":"info","sid":"$sid",""" +
            """"msg":"shell.turn.inference_done","ctx":{"loop_index":1,"model_elapsed_ms":11295,"attempts":1,""" +
            """"prompt_tokens":4138,"completion_tokens":351}}"""
}
