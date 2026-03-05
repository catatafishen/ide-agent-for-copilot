package com.github.catatafishen.ideagentforcopilot.ui.renderers

/**
 * Renderer for http_request output.
 * Input: "HTTP {code} {message}\n\n--- Headers ---\n...\n\n--- Body ---\n..."
 */
internal object HttpRequestRenderer : ToolResultRenderer {

    private val STATUS_LINE = Regex("""^HTTP\s+(\d+)\s+(.*)""")

    override fun render(output: String): String? {
        val statusMatch = output.lines().firstOrNull()?.let { STATUS_LINE.find(it.trim()) } ?: return null

        val code = statusMatch.groupValues[1].toIntOrNull() ?: return null
        val message = statusMatch.groupValues[2]

        val statusClass = when {
            code in 200..299 -> "success"
            code in 300..399 -> "warning"
            else -> "danger"
        }

        // Split sections
        val headersStart = output.indexOf("--- Headers ---")
        val bodyStart = output.indexOf("--- Body ---")

        val headers = if (headersStart >= 0) {
            val end = if (bodyStart >= 0) bodyStart else output.length
            output.substring(headersStart + "--- Headers ---".length, end)
                .trim().lines().filter { it.isNotBlank() }
        } else emptyList()

        val body = if (bodyStart >= 0) {
            output.substring(bodyStart + "--- Body ---".length).trim()
        } else ""

        val e = ToolRenderers::esc
        val sb = StringBuilder()
        sb.append("<div class='http-result'>")

        // Status header
        sb.append("<div class='http-status http-status-$statusClass'>")
        sb.append("<span class='http-method'>HTTP</span> ")
        sb.append("<span class='http-code'>$code</span> ")
        sb.append("<span class='http-message'>${e(message)}</span>")
        sb.append("</div>")

        // Headers
        if (headers.isNotEmpty()) {
            sb.append("<div class='http-section'>")
            sb.append("<div class='http-section-title'>Headers")
            sb.append(" <span class='inspection-file-count'>${headers.size}</span>")
            sb.append("</div>")
            sb.append("<div class='http-headers'>")
            for (h in headers) {
                val colonIdx = h.indexOf(':')
                if (colonIdx > 0) {
                    val key = h.substring(0, colonIdx).trim()
                    val value = h.substring(colonIdx + 1).trim()
                    sb.append("<div class='http-header'>")
                    sb.append("<span class='http-header-key'>${e(key)}</span>")
                    sb.append("<span class='http-header-value'>${e(value)}</span>")
                    sb.append("</div>")
                } else {
                    sb.append("<div class='http-header'>${e(h)}</div>")
                }
            }
            sb.append("</div></div>")
        }

        // Body
        if (body.isNotEmpty()) {
            sb.append("<div class='http-section'>")
            sb.append("<div class='http-section-title'>Body</div>")
            sb.append("<pre class='http-body'><code>${e(body)}</code></pre>")
            sb.append("</div>")
        }

        sb.append("</div>")
        return sb.toString()
    }
}
