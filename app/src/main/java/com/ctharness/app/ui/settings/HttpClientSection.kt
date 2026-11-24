package com.ctharness.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.ctharness.app.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * A small HTTP client for the agent workspace: any method, any header, a body,
 * and the response with its status, time and the first 20 KB. Nothing is
 * persisted; the clipboard is one tap away for pasting into a chat.
 */
@Composable
internal fun HttpClientSection() {
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var method by rememberSaveable { mutableStateOf("GET") }
    var url by rememberSaveable { mutableStateOf("") }
    var headers by rememberSaveable { mutableStateOf("") }
    var body by rememberSaveable { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var response by remember { mutableStateOf("") }
    val client = remember {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    fun send() {
        val address = url.trim()
        if (address.isEmpty() || sending) return
        if (!address.startsWith("http://") && !address.startsWith("https://")) {
            response = "The URL must start with http:// or https://"
            return
        }
        sending = true
        response = ""
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                val started = System.currentTimeMillis()
                runCatching {
                    val mediaType = "application/json; charset=utf-8".toMediaTypeOrNull()
                    val payload = if (method == "GET" || method == "HEAD" || body.isEmpty()) null
                    else body.toRequestBody(mediaType)
                    val builder = Request.Builder().url(address).method(method, payload)
                    headers.lineSequence().forEach { line ->
                        val colon = line.indexOf(':')
                        if (colon > 0) builder.header(line.substring(0, colon).trim(), line.substring(colon + 1).trim())
                    }
                    client.newCall(builder.build()).execute().use { answer ->
                        val bytes = answer.body?.bytes() ?: ByteArray(0)
                        val readable = runCatching { String(bytes, Charsets.UTF_8) }.getOrDefault("")
                        buildString {
                            append(answer.code).append(' ').append(answer.message).append(" · ")
                            append(System.currentTimeMillis() - started).append(" ms · ")
                            append(bytes.size).append(" bytes\n")
                            answer.headers.names().forEach { name ->
                                append("$name: ").append(answer.header(name).orEmpty()).append('\n')
                            }
                            append('\n')
                            append(readable.take(20_000))
                            if (readable.length > 20_000) append("\n… ${readable.length - 20_000} more characters")
                        }
                    }
                }.getOrElse { error ->
                    "Request failed: " + (error.message ?: error::class.simpleName)
                }
            }
            response = text
            sending = false
        }
    }

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SettingsHeader(stringResource(R.string.settings_page_http_title))
        SettingsPanel(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("GET", "POST", "PUT", "PATCH", "DELETE").forEach { option ->
                        FilterChip(selected = method == option, onClick = { method = option },
                            label = { Text(option, style = MaterialTheme.typography.labelMedium) })
                    }
                }
                OutlinedTextField(url, { url = it }, Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text(stringResource(R.string.http_client_url)) })
                OutlinedTextField(headers, { headers = it }, Modifier.fillMaxWidth(), minLines = 2, maxLines = 5,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    label = { Text(stringResource(R.string.http_client_headers)) })
                if (method != "GET" && method != "HEAD") {
                    OutlinedTextField(body, { body = it }, Modifier.fillMaxWidth(), minLines = 3, maxLines = 8,
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        label = { Text(stringResource(R.string.http_client_body)) })
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { send() }, enabled = !sending) {
                        Text(stringResource(if (sending) R.string.http_client_sending else R.string.http_client_send))
                    }
                    if (response.isNotBlank()) {
                        TextButton(onClick = { clipboard.setText(AnnotatedString(response)) }) {
                            Text(stringResource(R.string.http_client_copy))
                        }
                    }
                }
            }
        }
        if (response.isNotBlank()) {
            SettingsHeader(stringResource(R.string.http_client_response))
            SettingsPanel(Modifier.fillMaxWidth()) {
                SelectionContainer {
                    Text(response, Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}
