package app.safetube.core

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI

/** Tiny HTTP helper so the core has no dependencies beyond the JDK / Android platform. */
object Http {
    data class Response(val code: Int, val body: String)

    fun request(
        url: String,
        method: String = "GET",
        headers: Map<String, String> = emptyMap(),
        body: String? = null,
        timeoutMs: Int = 30_000,
    ): Response {
        val conn = (URI(url).toURL().openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
            if (body != null) {
                doOutput = true
                setRequestProperty("content-type", "application/json")
            }
        }
        try {
            if (body != null) conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            return Response(code, text)
        } catch (e: IOException) {
            throw ClassifierException("Network error talking to $url: ${e.message}", e)
        } finally {
            conn.disconnect()
        }
    }
}
