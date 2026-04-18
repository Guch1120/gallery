/*
 * Copyright 2025 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.ai.edge.gallery.edgeserver

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import android.util.Base64
import com.google.ai.edge.gallery.data.ConfigKeys
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.runtime.LlmModelHelper
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.stream.JsonReader
import java.io.File
import java.io.IOException
import java.io.InputStream
import fi.iki.elonen.NanoHTTPD
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "EdgeServer"

/**
 * On-device HTTP server that exposes an OpenAI-compatible REST API backed by
 * AI Edge Gallery's GPU-accelerated LLM inference.
 *
 * This allows any OpenAI-compatible client (curl, Open WebUI, custom scripts,
 * etc.) to interact with on-device models over localhost without cloud access.
 *
 * Endpoints:
 *   GET  /health                  → server & model status
 *   GET  /v1/models               → list loaded models
 *   POST /v1/chat/completions     → chat completions (streaming & non-streaming)
 */
class EdgeServer(
  hostname: String = DEFAULT_HOST,
  port: Int = DEFAULT_PORT,
  private val timeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS,
) : NanoHTTPD(hostname, port) {

  companion object {
    const val DEFAULT_HOST = "0.0.0.0"
    const val DEFAULT_PORT = 8888
    const val DEFAULT_TIMEOUT_SECONDS = 300L
    private const val MIME_JSON = "application/json; charset=utf-8"
  }

  /** The active model, set via [EdgeServerManager.bindModel]. */
  @Volatile var activeModel: Model? = null
  @Volatile var activeModelHelper: LlmModelHelper? = null
  @Volatile var activeModelDisplayName: String = ""

  /**
   * Optional callback invoked when a request arrives but no model is bound.
   * The callback should attempt to discover and initialize a downloaded model.
   */
  @Volatile var modelFinder: (() -> Unit)? = null

  private val inferenceSemaphore = Semaphore(1, true)
  private val gson = Gson()

  private data class ChatRequestPayload(
    val prompt: String,
    val images: List<Bitmap>,
    val promptPreview: String,
    val thinkingEnabled: Boolean,
    val maxTokens: Int,
    val topK: Int,
    val topP: Float,
    val temperature: Float,
  )

  private data class ModelConfigSnapshot(
    val configValues: Map<String, Any>,
  )

  private class StreamingInputStream : InputStream() {
    private val chunks = LinkedBlockingQueue<ByteArray>()
    @Volatile private var finished = false
    @Volatile private var closed = false
    private var currentChunk = ByteArray(0)
    private var currentIndex = 0

    fun enqueue(text: String): Boolean = enqueue(text.toByteArray(StandardCharsets.UTF_8))

    fun enqueue(bytes: ByteArray): Boolean {
      if (closed) {
        return false
      }
      chunks.put(bytes)
      return !closed
    }

    fun finish() {
      finished = true
    }

    fun isClosedForProducer(): Boolean = closed

    override fun read(): Int {
      val oneByte = ByteArray(1)
      val count = read(oneByte, 0, 1)
      return if (count == -1) -1 else oneByte[0].toInt() and 0xFF
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
      if (length == 0) {
        return 0
      }
      while (currentIndex >= currentChunk.size) {
        if ((finished || closed) && chunks.isEmpty()) {
          return -1
        }
        val nextChunk = chunks.poll(250, TimeUnit.MILLISECONDS) ?: continue
        currentChunk = nextChunk
        currentIndex = 0
      }

      val bytesToCopy = minOf(length, currentChunk.size - currentIndex)
      System.arraycopy(currentChunk, currentIndex, buffer, offset, bytesToCopy)
      currentIndex += bytesToCopy
      return bytesToCopy
    }

    override fun close() {
      closed = true
      finished = true
    }
  }

  // ───────────────────────────────────────────────────────────────────────
  // Request routing
  // ───────────────────────────────────────────────────────────────────────

  override fun serve(session: IHTTPSession): Response {
    val uri = session.uri ?: ""
    val method = session.method

    // Auto-discover model if not yet bound.
    if (activeModel?.instance == null) {
      tryAutoDiscoverModel()
    }

    return try {
      when {
        uri == "/health" -> handleHealth()
        uri == "/v1/models" && method == Method.GET -> handleListModels()
        uri == "/v1/chat/completions" && method == Method.POST -> handleChatCompletions(session)
        method == Method.OPTIONS -> newFixedLengthResponse(
          Response.Status.OK, MIME_PLAINTEXT, ""
        ).applyCors()
        else -> newFixedLengthResponse(
          Response.Status.NOT_FOUND, MIME_JSON,
          """{"error":{"message":"Not found: $uri","type":"invalid_request_error"}}"""
        )
      }
    } catch (e: Exception) {
      Log.e(TAG, "Unhandled server error", e)
      errorResponse(500, e.message ?: "Internal server error")
    }
  }

  // ───────────────────────────────────────────────────────────────────────
  // GET /health
  // ───────────────────────────────────────────────────────────────────────

  private fun handleHealth(): Response {
    val loaded = activeModel?.instance != null
    val json = """{"status":"ok","model_loaded":$loaded,"model":"$activeModelDisplayName"}"""
    return newFixedLengthResponse(Response.Status.OK, MIME_JSON, json).applyCors()
  }

  // ───────────────────────────────────────────────────────────────────────
  // GET /v1/models
  // ───────────────────────────────────────────────────────────────────────

  private fun handleListModels(): Response {
    val model = activeModel
    val body = if (model != null) {
      val obj = JsonObject().apply {
        addProperty("id", activeModelDisplayName.ifEmpty { model.name })
        addProperty("object", "model")
        addProperty("owned_by", "ai-edge-gallery")
      }
      """{"object":"list","data":[${gson.toJson(obj)}]}"""
    } else {
      """{"object":"list","data":[]}"""
    }
    return newFixedLengthResponse(Response.Status.OK, MIME_JSON, body).applyCors()
  }

  // ───────────────────────────────────────────────────────────────────────
  // POST /v1/chat/completions
  // ───────────────────────────────────────────────────────────────────────

  private fun handleChatCompletions(session: IHTTPSession): Response {
    // NanoHTTPD requires parseBody() before reading POST data.
    val bodyFiles = HashMap<String, String>()
    try {
      session.parseBody(bodyFiles)
    } catch (e: Exception) {
      return errorResponse(400, "Failed to parse request body: ${e.message}")
    }

    val bodyStr = readRequestBody(bodyFiles)
    if (bodyStr.isEmpty()) {
      return errorResponse(400, "Empty request body")
    }

    val body: JsonObject = try {
      val reader = JsonReader(java.io.StringReader(bodyStr))
      reader.isLenient = true
      JsonParser.parseReader(reader).asJsonObject
    } catch (e: Exception) {
      return errorResponse(400, "Invalid JSON: ${e.message}")
    }

    val model = activeModel
    val helper = activeModelHelper
    if (model == null || helper == null || model.instance == null) {
      return errorResponse(503, "No model loaded. Open the Gallery app and load a model first.")
    }

    val messages = body.getAsJsonArray("messages")
    if (messages == null || messages.size() == 0) {
      return errorResponse(400, "\"messages\" array is required and must not be empty")
    }

    val requestedThinking = readThinkingFlag(body)
    val payload =
      buildPromptPayload(
        messages = messages,
        thinkingEnabled = EdgeServerManager.resolveThinkingEnabled(requestedThinking),
        maxTokens = EdgeServerManager.resolveMaxTokens(readIntField(body, "max_tokens", "maxTokens")),
        topK = EdgeServerManager.resolveTopK(readIntField(body, "top_k", "topK", "topk")),
        topP = EdgeServerManager.resolveTopP(readFloatField(body, "top_p", "topP", "topp")),
        temperature =
          EdgeServerManager.resolveTemperature(readFloatField(body, "temperature")),
      )
    Log.i(
      TAG,
      "Chat request received: images=${payload.images.size}, thinking=${payload.thinkingEnabled}, maxTokens=${payload.maxTokens}, topK=${payload.topK}, topP=${payload.topP}, temperature=${payload.temperature}",
    )
    val stream = body.get("stream")?.asBoolean ?: false
    val requestId = "chatcmpl-${UUID.randomUUID().toString().take(12)}"
    val modelId = activeModelDisplayName.ifEmpty { model.name }

    return if (stream) {
      handleStreamingResponse(model, helper, payload, requestId, modelId)
    } else {
      handleNonStreamingResponse(model, helper, payload, requestId, modelId)
    }
  }

  // ───────────────────────────────────────────────────────────────────────
  // Prompt builder
  // ───────────────────────────────────────────────────────────────────────

  /**
   * Converts an OpenAI-style messages array into a Gemma prompt string.
   *
   * Handles three `content` shapes:
   *  - String literal
   *  - Array of `{"type":"text","text":"..."}` (multi-modal format)
   *  - Single object with a `text` field
   */
  private fun buildPromptPayload(
    messages: JsonArray,
    thinkingEnabled: Boolean,
    maxTokens: Int,
    topK: Int,
    topP: Float,
    temperature: Float,
  ): ChatRequestPayload {
    val images = mutableListOf<Bitmap>()
    val previewLines = mutableListOf<String>()
    val prompt =
      buildString {
        for (el in messages) {
          val obj = el.asJsonObject
          val role = normalizeRole(obj.get("role")?.asString)
          val content = extractContent(obj.get("content"), images)
          if (content.isNotEmpty()) {
            append("<start_of_turn>$role\n$content<end_of_turn>\n")
            previewLines.add("$role: $content")
          } else if (images.isNotEmpty()) {
            previewLines.add("$role: [image x${images.size}]")
          }
        }
        append("<start_of_turn>model\n")
      }
    return ChatRequestPayload(
      prompt = prompt,
      images = images,
      promptPreview = previewLines.joinToString(separator = "\n").take(2000),
      thinkingEnabled = thinkingEnabled,
      maxTokens = maxTokens,
      topK = topK,
      topP = topP,
      temperature = temperature,
    )
  }

  /** Extracts text from an OpenAI `content` field (String | Array | Object | null). */
  private fun extractContent(element: JsonElement?, images: MutableList<Bitmap>): String {
    if (element == null || element.isJsonNull) return ""
    if (element.isJsonPrimitive) return element.asString
    if (element.isJsonArray) {
      return buildString {
        for (part in element.asJsonArray) {
          if (part.isJsonObject) {
            val obj = part.asJsonObject
            when (obj.get("type")?.asString ?: "") {
              "text" -> obj.get("text")?.asString?.let { append(it) }
              "image_url" -> decodeImagePart(obj)?.let { images.add(it) }
              else -> obj.get("text")?.asString?.let { append(it) }
            }
          } else if (part.isJsonPrimitive) {
            append(part.asString)
          }
        }
      }
    }
    if (element.isJsonObject) {
      val obj = element.asJsonObject
      obj.get("text")?.asString?.let { return it }
      decodeImagePart(obj)?.let {
        images.add(it)
        return ""
      }
      return element.toString()
    }
    return ""
  }

  // ───────────────────────────────────────────────────────────────────────
  // Streaming response (SSE)
  // ───────────────────────────────────────────────────────────────────────

  private fun handleStreamingResponse(
    model: Model, helper: LlmModelHelper, payload: ChatRequestPayload,
    requestId: String, modelId: String,
  ): Response {
    if (!inferenceSemaphore.tryAcquire(5, TimeUnit.SECONDS)) {
      return errorResponse(429, "Server busy. Try again later.")
    }

    val configSnapshot = applyRequestConfig(model, helper, payload)
    EdgeServerManager.recordRequestStart(
      payload.promptPreview,
      payload.images.size,
      payload.thinkingEnabled,
    )

    val streamingInput = StreamingInputStream()
    val done = AtomicBoolean(false)

    Thread {
      try {
        val latch = CountDownLatch(1)
        fun finishStream(
          finishReason: String? = "stop",
          errorMessage: String? = null,
        ) {
          if (!done.compareAndSet(false, true)) {
            return
          }
          try {
            if (errorMessage != null) {
              streamingInput.enqueue(
                "data: {\"error\":{\"message\":\"${escapeJson(errorMessage)}\"}}\n\n"
              )
            } else {
              streamingInput.enqueue(
                "data: ${sseChunk(requestId, modelId, "", finishReason)}\n\n"
              )
            }
            streamingInput.enqueue("data: [DONE]\n\n")
          } catch (e: Exception) {
            if (streamingInput.isClosedForProducer()) {
              Log.i(TAG, "Streaming client disconnected")
            } else {
              Log.e(TAG, "SSE finalize error", e)
            }
          } finally {
            streamingInput.finish()
            if (errorMessage != null) {
              EdgeServerManager.recordRequestError(errorMessage)
            } else {
              EdgeServerManager.recordRequestDone()
            }
            latch.countDown()
          }
        }
        helper.runInference(
          model = model,
          input = payload.prompt,
          images = payload.images,
          resultListener = { partial, isDone, partialThinking ->
            try {
              if (done.get()) {
                return@runInference
              }
              if (partialThinking != null && partialThinking.isNotEmpty()) {
                EdgeServerManager.appendThinkingChunk(partialThinking)
              }
              if (partial.isNotEmpty()) {
                EdgeServerManager.appendResponseChunk(partial)
                val chunk = sseChunk(requestId, modelId, partial, null)
                if (!streamingInput.enqueue("data: $chunk\n\n")) {
                  Log.i(TAG, "Streaming client disconnected")
                  helper.stopResponse(model)
                  if (done.compareAndSet(false, true)) {
                    EdgeServerManager.recordRequestDone()
                    latch.countDown()
                  }
                }
              }
              if (isDone) {
                finishStream()
              }
            } catch (e: Exception) {
              if (streamingInput.isClosedForProducer()) {
                Log.i(TAG, "Streaming client disconnected")
                helper.stopResponse(model)
                if (done.compareAndSet(false, true)) {
                  EdgeServerManager.recordRequestDone()
                  latch.countDown()
                }
              } else {
                Log.e(TAG, "SSE write error", e)
                finishStream(errorMessage = e.message ?: "Streaming write failed")
              }
            }
          },
          cleanUpListener = {
            if (!done.get()) {
              finishStream()
            }
          },
          onError = { msg ->
            finishStream(errorMessage = msg)
          },
          extraContext =
            if (payload.thinkingEnabled) mapOf("enable_thinking" to "true") else emptyMap(),
        )
        latch.await(timeoutSeconds, TimeUnit.SECONDS)
      } catch (e: Exception) {
        Log.e(TAG, "Inference error", e)
        EdgeServerManager.recordRequestError(e.message ?: "Inference failed")
      } finally {
        restoreModelConfig(model, helper, configSnapshot)
        streamingInput.finish()
        inferenceSemaphore.release()
      }
    }.start()

    return newChunkedResponse(
      Response.Status.OK,
      "text/event-stream; charset=utf-8",
      streamingInput,
    ).apply {
      addHeader("Cache-Control", "no-cache")
      addHeader("Connection", "keep-alive")
    }.applyCors()
  }

  // ───────────────────────────────────────────────────────────────────────
  // Non-streaming response
  // ───────────────────────────────────────────────────────────────────────

  private fun handleNonStreamingResponse(
    model: Model, helper: LlmModelHelper, payload: ChatRequestPayload,
    requestId: String, modelId: String,
  ): Response {
    if (!inferenceSemaphore.tryAcquire(5, TimeUnit.SECONDS)) {
      return errorResponse(429, "Server busy. Try again later.")
    }
    val configSnapshot = applyRequestConfig(model, helper, payload)
    try {
      EdgeServerManager.recordRequestStart(
        payload.promptPreview,
        payload.images.size,
        payload.thinkingEnabled,
      )
      val result = StringBuilder()
      val latch = CountDownLatch(1)
      var errorMsg: String? = null

      helper.runInference(
        model = model,
        input = payload.prompt,
        images = payload.images,
        resultListener = { partial, isDone, partialThinking ->
          if (partialThinking != null && partialThinking.isNotEmpty()) {
            EdgeServerManager.appendThinkingChunk(partialThinking)
          }
          if (partial.isNotEmpty()) {
            result.append(partial)
            EdgeServerManager.appendResponseChunk(partial)
          }
          if (isDone) {
            EdgeServerManager.recordRequestDone()
            latch.countDown()
          }
        },
        cleanUpListener = {
          EdgeServerManager.recordRequestDone()
          latch.countDown()
        },
        onError = { msg ->
          errorMsg = msg
          EdgeServerManager.recordRequestError(msg)
          latch.countDown()
        },
        extraContext =
          if (payload.thinkingEnabled) mapOf("enable_thinking" to "true") else emptyMap(),
      )

      if (!latch.await(timeoutSeconds, TimeUnit.SECONDS)) {
        EdgeServerManager.recordRequestError("Inference timed out after ${timeoutSeconds}s")
        return errorResponse(504, "Inference timed out after ${timeoutSeconds}s")
      }
      if (errorMsg != null) {
        return errorResponse(500, "Inference error: $errorMsg")
      }

      val json = buildString {
        append("""{"id":"$requestId","object":"chat.completion",""")
        append(""""created":${System.currentTimeMillis() / 1000},"model":"$modelId",""")
        append(""""choices":[{"index":0,"message":{"role":"assistant",""")
        append(""""content":"${escapeJson(result.toString())}"},"finish_reason":"stop"}],""")
        append(""""usage":{"prompt_tokens":0,"completion_tokens":0,"total_tokens":0}}""")
      }
      return newFixedLengthResponse(Response.Status.OK, MIME_JSON, json).applyCors()
    } finally {
      restoreModelConfig(model, helper, configSnapshot)
      inferenceSemaphore.release()
    }
  }

  // ───────────────────────────────────────────────────────────────────────
  // Helpers
  // ───────────────────────────────────────────────────────────────────────

  private fun tryAutoDiscoverModel() {
    val finder = modelFinder ?: return
    Log.i(TAG, "No model bound — invoking modelFinder...")
    finder.invoke()
    // Wait up to 90s for model initialization (GPU init can be slow).
    var waited = 0L
    while (activeModel?.instance == null && waited < 90_000L) {
      Thread.sleep(2_000L)
      waited += 2_000L
      if (waited % 10_000L == 0L) Log.i(TAG, "Waiting for model init... (${waited / 1000}s)")
    }
    if (activeModel?.instance != null) {
      Log.i(TAG, "Model available after ${waited / 1000}s")
    } else {
      Log.w(TAG, "Model not available after ${waited / 1000}s")
    }
  }

  private fun normalizeRole(rawRole: String?): String {
    return when (rawRole?.lowercase()) {
      "assistant", "model" -> "model"
      "system", "developer", "tool" -> "user"
      else -> "user"
    }
  }

  private fun readThinkingFlag(body: JsonObject): Boolean? {
    val directFlag = body.get("thinking")?.takeIf { !it.isJsonNull }?.asBoolean
    if (directFlag != null) {
      return directFlag
    }
    val legacyFlag = body.get("enable_thinking")?.takeIf { !it.isJsonNull }?.asBoolean
    if (legacyFlag != null) {
      return legacyFlag
    }
    return null
  }

  private fun readIntField(body: JsonObject, vararg names: String): Int? {
    for (name in names) {
      val value = body.get(name) ?: continue
      if (!value.isJsonNull) {
        return try {
          value.asInt
        } catch (_: Exception) {
          null
        }
      }
    }
    return null
  }

  private fun readFloatField(body: JsonObject, vararg names: String): Float? {
    for (name in names) {
      val value = body.get(name) ?: continue
      if (!value.isJsonNull) {
        return try {
          value.asFloat
        } catch (_: Exception) {
          null
        }
      }
    }
    return null
  }

  private fun applyRequestConfig(
    model: Model,
    helper: LlmModelHelper,
    payload: ChatRequestPayload,
  ): ModelConfigSnapshot {
    val snapshot = ModelConfigSnapshot(configValues = model.configValues.toMap())
    val nextConfigValues = snapshot.configValues.toMutableMap()
    nextConfigValues[ConfigKeys.MAX_TOKENS.label] = payload.maxTokens
    nextConfigValues[ConfigKeys.TOPK.label] = payload.topK
    nextConfigValues[ConfigKeys.TOPP.label] = payload.topP
    nextConfigValues[ConfigKeys.TEMPERATURE.label] = payload.temperature
    nextConfigValues[ConfigKeys.ENABLE_THINKING.label] = payload.thinkingEnabled
    model.configValues = nextConfigValues
    // Edge Server は OpenAI 互換 API として毎回完全な messages を受け取るので、
    // 前回の会話状態を引きずらないよう毎リクエスト前に会話をリセットする。
    helper.resetConversation(
      model = model,
      supportImage = EdgeServerManager.supportsImage(),
      supportAudio = EdgeServerManager.supportsAudio(),
    )
    return snapshot
  }

  private fun restoreModelConfig(
    model: Model,
    helper: LlmModelHelper,
    snapshot: ModelConfigSnapshot,
  ) {
    model.configValues = snapshot.configValues
    helper.resetConversation(
      model = model,
      supportImage = EdgeServerManager.supportsImage(),
      supportAudio = EdgeServerManager.supportsAudio(),
    )
  }

  private fun readRequestBody(bodyFiles: Map<String, String>): String {
    val postData = bodyFiles["postData"] ?: return ""
    if (postData.trimStart().startsWith("{") || postData.trimStart().startsWith("[")) {
      return postData
    }
    val file = File(postData)
    return if (file.exists()) file.readText(StandardCharsets.UTF_8) else postData
  }

  private fun decodeImagePart(obj: JsonObject): Bitmap? {
    val imageUrl = obj.getAsJsonObject("image_url") ?: return null
    val url = imageUrl.get("url")?.asString ?: return null
    return decodeBitmapFromImageUrl(url)
  }

  private fun decodeBitmapFromImageUrl(url: String): Bitmap? {
    val rawBase64 =
      when {
        url.startsWith("data:", ignoreCase = true) -> url.substringAfter("base64,", "")
        url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true) ->
          return null
        else -> url
      }
    if (rawBase64.isEmpty()) {
      return null
    }
    return try {
      val bytes = Base64.decode(rawBase64, Base64.DEFAULT)
      BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    } catch (e: IllegalArgumentException) {
      Log.w(TAG, "Failed to decode image payload", e)
      null
    }
  }

  private fun sseChunk(id: String, model: String, content: String, finishReason: String?): String {
    val delta = if (content.isNotEmpty()) """{"content":"${escapeJson(content)}"}""" else "{}"
    val reason = if (finishReason != null) "\"$finishReason\"" else "null"
    return """{"id":"$id","object":"chat.completion.chunk","created":${System.currentTimeMillis() / 1000},"model":"$model","choices":[{"index":0,"delta":$delta,"finish_reason":$reason}]}"""
  }

  private fun escapeJson(s: String): String = s
    .replace("\\", "\\\\").replace("\"", "\\\"")
    .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")

  private fun errorResponse(code: Int, message: String): Response {
    val status = when (code) {
      400 -> Response.Status.BAD_REQUEST
      404 -> Response.Status.NOT_FOUND
      429 -> Response.Status.lookup(429) ?: Response.Status.INTERNAL_ERROR
      503 -> Response.Status.lookup(503) ?: Response.Status.INTERNAL_ERROR
      504 -> Response.Status.lookup(504) ?: Response.Status.INTERNAL_ERROR
      else -> Response.Status.INTERNAL_ERROR
    }
    val json = """{"error":{"message":"${escapeJson(message)}","type":"server_error","code":$code}}"""
    return newFixedLengthResponse(status, MIME_JSON, json).applyCors()
  }

  private fun Response.applyCors(): Response {
    addHeader("Access-Control-Allow-Origin", "*")
    addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
    addHeader("Access-Control-Allow-Headers", "Content-Type, Authorization")
    return this
  }
}
