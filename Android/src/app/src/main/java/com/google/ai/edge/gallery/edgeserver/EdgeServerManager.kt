package com.google.ai.edge.gallery.edgeserver

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import com.google.ai.edge.gallery.data.ConfigKeys
import com.google.ai.edge.gallery.data.DEFAULT_MAX_TOKEN
import com.google.ai.edge.gallery.data.DEFAULT_TEMPERATURE
import com.google.ai.edge.gallery.data.DEFAULT_TOPK
import com.google.ai.edge.gallery.data.DEFAULT_TOPP
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.runtime.LlmModelHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val TAG = "EdgeServerManager"

/**
 * Singleton that manages the Edge Server lifecycle and exposes observable
 * state for the Compose UI.
 *
 * 修正版ポイント:
 * - bindModel() されたモデルを remembered* に保持する
 * - 後から server / service が起動しても remembered* を再適用する
 */
object EdgeServerManager {

  data class ServerState(
    val isRunning: Boolean = false,
    val host: String = EdgeServer.DEFAULT_HOST,
    val port: Int = EdgeServer.DEFAULT_PORT,
    val modelName: String = "",
    val modelReady: Boolean = false,
    val modelSupportsThinking: Boolean = false,
    val thinkingEnabled: Boolean = false,
    val maxTokens: Int = DEFAULT_MAX_TOKEN,
    val topK: Int = DEFAULT_TOPK,
    val topP: Float = DEFAULT_TOPP,
    val temperature: Float = DEFAULT_TEMPERATURE,
    val latestRequest: String = "",
    val latestResponse: String = "",
    val latestThinkingResponse: String = "",
    val latestThinkingEnabled: Boolean = false,
    val latestImageCount: Int = 0,
    val requestInProgress: Boolean = false,
    val latestError: String = "",
  )

  private val _state = MutableStateFlow(ServerState())
  val state: StateFlow<ServerState> = _state.asStateFlow()

  /** Direct server reference for immediate model binding. */
  @Volatile
  var server: EdgeServer? = null
    private set

  /** Callback invoked by the server when it needs a model. Set by NavGraph. */
  @Volatile
  var modelFinderCallback: (() -> Unit)? = null

  private var service: EdgeServerService? = null
  private var bound = false

  // ───────────────────────────────────────────────────────────────────────
  // 覚えておくモデル
  // ───────────────────────────────────────────────────────────────────────
  @Volatile private var rememberedModel: Model? = null
  @Volatile private var rememberedHelper: LlmModelHelper? = null
  @Volatile private var rememberedDisplayName: String = ""
  @Volatile private var rememberedSupportImage: Boolean = false
  @Volatile private var rememberedSupportAudio: Boolean = false
  @Volatile private var rememberedSupportThinking: Boolean = false
  @Volatile private var rememberedThinkingEnabled: Boolean = false
  @Volatile private var rememberedMaxTokens: Int = DEFAULT_MAX_TOKEN
  @Volatile private var rememberedTopK: Int = DEFAULT_TOPK
  @Volatile private var rememberedTopP: Float = DEFAULT_TOPP
  @Volatile private var rememberedTemperature: Float = DEFAULT_TEMPERATURE

  private val connection = object : ServiceConnection {
    override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
      service = (binder as EdgeServerService.LocalBinder).getService()
      server = service?.getServer()
      bound = true
      applyRememberedModelToServer()
      applyRememberedModelToService()
      refreshState()
      Log.i(TAG, "Service connected")
    }

    override fun onServiceDisconnected(name: ComponentName?) {
      server = null
      service = null
      bound = false
      refreshState()
      Log.i(TAG, "Service disconnected")
    }
  }

  /** Start the Edge Server on [host]:[port] with a foreground service. */
  fun startServer(
    context: Context,
    host: String = EdgeServer.DEFAULT_HOST,
    port: Int = EdgeServer.DEFAULT_PORT,
  ) {
    val intent = Intent(context, EdgeServerService::class.java).apply {
      putExtra("host", host)
      putExtra("port", port)
    }

    context.startForegroundService(intent)
    context.bindService(intent, connection, Context.BIND_AUTO_CREATE)

    _state.value = _state.value.copy(
      isRunning = true,
      host = host,
      port = port,
      modelName = rememberedDisplayName,
      modelReady = rememberedModel?.instance != null,
      modelSupportsThinking = rememberedSupportThinking,
      thinkingEnabled = rememberedThinkingEnabled && rememberedSupportThinking,
      maxTokens = rememberedMaxTokens,
      topK = rememberedTopK,
      topP = rememberedTopP,
      temperature = rememberedTemperature,
    )
  }

  /** Stop the server and foreground service. */
  fun stopServer(context: Context) {
    server = null

    if (bound) {
      try {
        context.unbindService(connection)
      } catch (e: Exception) {
        Log.w(TAG, "Unbind error: ${e.message}")
      }
      bound = false
    }

    context.stopService(Intent(context, EdgeServerService::class.java))
    service = null

    // rememberedModel は消さない
    _state.value = _state.value.copy(
      isRunning = false,
      modelName = rememberedDisplayName,
      modelReady = rememberedModel?.instance != null,
      modelSupportsThinking = rememberedSupportThinking,
      thinkingEnabled = rememberedThinkingEnabled && rememberedSupportThinking,
      maxTokens = rememberedMaxTokens,
      topK = rememberedTopK,
      topP = rememberedTopP,
      temperature = rememberedTemperature,
    )

    Log.i(TAG, "Server stopped")
  }

  /** Bind a loaded model so the server can serve inference requests. */
  fun bindModel(
    model: Model,
    helper: LlmModelHelper,
    displayName: String,
    supportImage: Boolean = false,
    supportAudio: Boolean = false,
    supportThinking: Boolean = false,
  ) {
    val currentModel = rememberedModel
    val currentScore = capabilityScore(rememberedSupportImage, rememberedSupportAudio)
    val newScore = capabilityScore(supportImage, supportAudio)
    if (currentModel?.name == model.name && currentScore > newScore) {
      Log.i(
        TAG,
        "Keeping existing Edge Server binding for '${model.name}' because it has richer IO support",
      )
      return
    }

    rememberedModel = model
    rememberedHelper = helper
    rememberedDisplayName = displayName
    rememberedSupportImage = supportImage
    rememberedSupportAudio = supportAudio
    rememberedSupportThinking = supportThinking
    rememberedMaxTokens =
      model.getIntConfigValue(key = ConfigKeys.MAX_TOKENS, defaultValue = DEFAULT_MAX_TOKEN)
    rememberedTopK = model.getIntConfigValue(key = ConfigKeys.TOPK, defaultValue = DEFAULT_TOPK)
    rememberedTopP = model.getFloatConfigValue(key = ConfigKeys.TOPP, defaultValue = DEFAULT_TOPP)
    rememberedTemperature =
      model.getFloatConfigValue(key = ConfigKeys.TEMPERATURE, defaultValue = DEFAULT_TEMPERATURE)
    if (!supportThinking) {
      rememberedThinkingEnabled = false
    }

    // 今いる server / service に適用
    applyRememberedModelToServer()
    applyRememberedModelToService()

    _state.value =
      _state.value.copy(
        modelName = displayName,
        modelReady = model.instance != null,
        modelSupportsThinking = supportThinking,
        thinkingEnabled = rememberedThinkingEnabled && supportThinking,
        maxTokens = rememberedMaxTokens,
        topK = rememberedTopK,
        topP = rememberedTopP,
        temperature = rememberedTemperature,
      )
    Log.i(TAG, "Model bound: $displayName")
  }

  /** Unbind the current model. */
  fun unbindModel() {
    rememberedModel = null
    rememberedHelper = null
    rememberedDisplayName = ""
    rememberedSupportImage = false
    rememberedSupportAudio = false
    rememberedSupportThinking = false
    rememberedThinkingEnabled = false
    rememberedMaxTokens = DEFAULT_MAX_TOKEN
    rememberedTopK = DEFAULT_TOPK
    rememberedTopP = DEFAULT_TOPP
    rememberedTemperature = DEFAULT_TEMPERATURE

    server?.activeModel = null
    server?.activeModelHelper = null
    server?.activeModelDisplayName = ""

    service?.clearActiveModel()

    _state.value =
      _state.value.copy(
        modelName = "",
        modelReady = false,
        modelSupportsThinking = false,
        thinkingEnabled = false,
        maxTokens = rememberedMaxTokens,
        topK = rememberedTopK,
        topP = rememberedTopP,
        temperature = rememberedTemperature,
      )
  }

  fun setThinkingEnabled(enabled: Boolean) {
    rememberedThinkingEnabled = enabled && rememberedSupportThinking
    _state.value =
      _state.value.copy(
        thinkingEnabled = rememberedThinkingEnabled && rememberedSupportThinking,
        modelSupportsThinking = rememberedSupportThinking,
      )
  }

  fun resolveThinkingEnabled(requestedValue: Boolean?): Boolean {
    return when {
      !rememberedSupportThinking -> false
      requestedValue == null -> rememberedThinkingEnabled
      else -> requestedValue
    }
  }

  fun setSamplingConfig(maxTokens: Int, topK: Int, topP: Float, temperature: Float) {
    rememberedMaxTokens = maxTokens
    rememberedTopK = topK
    rememberedTopP = topP
    rememberedTemperature = temperature
    _state.value =
      _state.value.copy(
        maxTokens = rememberedMaxTokens,
        topK = rememberedTopK,
        topP = rememberedTopP,
        temperature = rememberedTemperature,
      )
  }

  fun resolveMaxTokens(requestedValue: Int?): Int = requestedValue ?: rememberedMaxTokens

  fun resolveTopK(requestedValue: Int?): Int = requestedValue ?: rememberedTopK

  fun resolveTopP(requestedValue: Float?): Float = requestedValue ?: rememberedTopP

  fun resolveTemperature(requestedValue: Float?): Float = requestedValue ?: rememberedTemperature

  fun supportsImage(): Boolean = rememberedSupportImage

  fun supportsAudio(): Boolean = rememberedSupportAudio

  private fun applyRememberedModelToServer() {
    val model = rememberedModel
    val helper = rememberedHelper
    val s = server
    if (model != null && helper != null && s != null) {
      s.activeModel = model
      s.activeModelHelper = helper
      s.activeModelDisplayName = rememberedDisplayName
      Log.i(TAG, "Rebound model to in-process server: $rememberedDisplayName")
    }
  }

  private fun applyRememberedModelToService() {
    val model = rememberedModel
    val helper = rememberedHelper
    val svc = service
    if (model != null && helper != null && svc != null) {
      svc.setActiveModel(model, helper, rememberedDisplayName)
      Log.i(TAG, "Rebound model to service: $rememberedDisplayName")
    }
  }

  private fun refreshState() {
    val running = server?.isAlive == true || service?.isServerRunning() == true
    val port = server?.listeningPort ?: service?.getPort() ?: _state.value.port
    _state.value = _state.value.copy(
      isRunning = running,
      port = port,
      modelName = rememberedDisplayName,
      modelReady = rememberedModel?.instance != null,
      modelSupportsThinking = rememberedSupportThinking,
      thinkingEnabled = rememberedThinkingEnabled && rememberedSupportThinking,
      maxTokens = rememberedMaxTokens,
      topK = rememberedTopK,
      topP = rememberedTopP,
      temperature = rememberedTemperature,
    )
  }

  fun recordRequestStart(prompt: String, imageCount: Int, thinkingEnabled: Boolean) {
    _state.value =
      _state.value.copy(
        latestRequest = prompt,
        latestResponse = "",
        latestThinkingResponse = "",
        latestThinkingEnabled = thinkingEnabled,
        latestImageCount = imageCount,
        requestInProgress = true,
        latestError = "",
      )
  }

  fun appendResponseChunk(chunk: String) {
    if (chunk.isEmpty()) {
      return
    }
    _state.value = _state.value.copy(latestResponse = _state.value.latestResponse + chunk)
  }

  fun appendThinkingChunk(chunk: String) {
    if (chunk.isEmpty()) {
      return
    }
    _state.value =
      _state.value.copy(latestThinkingResponse = _state.value.latestThinkingResponse + chunk)
  }

  fun recordRequestDone() {
    _state.value = _state.value.copy(requestInProgress = false)
  }

  fun recordRequestError(message: String) {
    _state.value =
      _state.value.copy(requestInProgress = false, latestError = message.ifEmpty { "Unknown error" })
  }

  private fun capabilityScore(supportImage: Boolean, supportAudio: Boolean): Int {
    var score = 0
    if (supportImage) {
      score += 2
    }
    if (supportAudio) {
      score += 1
    }
    return score
  }
}
