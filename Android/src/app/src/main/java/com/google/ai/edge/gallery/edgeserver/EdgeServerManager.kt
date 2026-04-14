package com.google.ai.edge.gallery.edgeserver

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
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
    val latestRequest: String = "",
    val latestResponse: String = "",
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

  private val connection = object : ServiceConnection {
    override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
      service = (binder as EdgeServerService.LocalBinder).getService()
      bound = true
      applyRememberedModelToService()
      refreshState()
      Log.i(TAG, "Service connected")
    }

    override fun onServiceDisconnected(name: ComponentName?) {
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
    if (server == null || !server!!.isAlive) {
      server = EdgeServer(hostname = host, port = port).also {
        it.modelFinder = modelFinderCallback
      }

      // ここで remembered モデルを先に再適用
      applyRememberedModelToServer()

      try {
        server?.start()
        Log.i(TAG, "Server started on $host:$port")
      } catch (e: Exception) {
        Log.e(TAG, "Failed to start server", e)
      }
    }

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
    )
  }

  /** Stop the server and foreground service. */
  fun stopServer(context: Context) {
    server?.stop()
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

    // 今いる server / service に適用
    applyRememberedModelToServer()
    applyRememberedModelToService()

    _state.value = _state.value.copy(modelName = displayName)
    Log.i(TAG, "Model bound: $displayName")
  }

  /** Unbind the current model. */
  fun unbindModel() {
    rememberedModel = null
    rememberedHelper = null
    rememberedDisplayName = ""
    rememberedSupportImage = false
    rememberedSupportAudio = false

    server?.activeModel = null
    server?.activeModelHelper = null
    server?.activeModelDisplayName = ""

    service?.clearActiveModel()

    _state.value = _state.value.copy(modelName = "")
  }

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
    )
  }

  fun recordRequestStart(prompt: String, imageCount: Int) {
    _state.value =
      _state.value.copy(
        latestRequest = prompt,
        latestResponse = "",
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
