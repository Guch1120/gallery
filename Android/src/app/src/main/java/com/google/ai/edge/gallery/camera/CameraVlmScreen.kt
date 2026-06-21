/*
 * Copyright 2026 Google LLC
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

package com.google.ai.edge.gallery.camera

import androidx.camera.core.CameraSelector
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.data.BuiltInTaskId
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.edgeserver.EdgeServerManager
import com.google.ai.edge.gallery.runtime.runtimeHelper
import com.google.ai.edge.gallery.ui.common.LiveCameraView
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.launch

private const val DEFAULT_CAMERA_PROMPT = "今カメラに写っている状況を日本語で簡潔に説明して。"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraVlmScreen(
  modelManagerViewModel: ModelManagerViewModel,
  onBack: () -> Unit,
) {
  val scope = rememberCoroutineScope()
  val uiState by modelManagerViewModel.uiState.collectAsState()
  val serverState by EdgeServerManager.state.collectAsState()
  val scrollState = rememberScrollState()
  var prompt by remember { mutableStateOf(DEFAULT_CAMERA_PROMPT) }
  var resultText by remember { mutableStateOf("最新フレームを取得中です。") }
  var statusText by remember { mutableStateOf("背面カメラを起動しています。") }
  var isInferencing by remember { mutableStateOf(false) }

  val target = remember(uiState) { findCameraVlmTarget(modelManagerViewModel) }
  val canInfer = !isInferencing && !serverState.requestInProgress && target != null
  val isAnyInferencing = isInferencing || serverState.requestInProgress
  val resultDisplayText =
    when {
      isInferencing -> resultText
      serverState.latestError.isNotBlank() -> serverState.latestError
      serverState.latestResponse.isNotBlank() -> serverState.latestResponse
      else -> resultText
    }
  val resultStatusText =
    when {
      isInferencing -> "ローカル推論中"
      serverState.requestInProgress -> "PCからのリクエストを推論中"
      serverState.latestError.isNotBlank() -> "Edge Server エラー"
      serverState.latestResponse.isNotBlank() -> "PCからの最新レスポンス"
      else -> statusText
    }

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text("Camera VLM") },
        navigationIcon = {
          IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
          }
        },
      )
    }
  ) { padding ->
    Column(
      modifier =
        Modifier.fillMaxSize()
          .padding(padding)
          .padding(horizontal = 20.dp)
          .verticalScroll(scrollState),
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      Spacer(Modifier.height(4.dp))

      Box(
        modifier =
          Modifier.fillMaxWidth()
            .height(360.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
      ) {
        LiveCameraView(
          onBitmap = { bitmap, imageProxy ->
            try {
              CameraFrameProvider.update(bitmap)
            } finally {
              imageProxy.close()
            }
          },
          cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA,
          preferredSize = 768,
          renderPreview = true,
          modifier = Modifier.fillMaxSize(),
        )
      }

      Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
      ) {
        Column(
          modifier = Modifier.padding(16.dp),
          verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          Text(
            text = "Inference",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
          )
          Text(
            text =
              target?.let { "Model: ${it.model.displayName.ifEmpty { it.model.name }}" }
                ?: "画像対応のダウンロード済みモデルがありません。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          OutlinedTextField(
            value = prompt,
            onValueChange = { prompt = it },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
            label = { Text("Prompt") },
          )
          Button(
            enabled = canInfer,
            onClick = {
              val currentTarget = target ?: return@Button
              val frame = CameraFrameProvider.sampleLatest()
              if (frame == null) {
                statusText = "まだカメラフレームを取得できていません。"
                resultText = "カメラプレビューが表示されてから再実行してください。"
                return@Button
              }

              isInferencing = true
              statusText = "推論準備中..."
              resultText = ""
              runCurrentFrameInference(
                target = currentTarget,
                frame = frame,
                prompt = prompt,
                onStatus = { statusText = it },
                onResult = { resultText = it },
                onFinished = { isInferencing = false },
                launchOnMain = { block -> scope.launch { block() } },
              )
            },
          ) {
            if (isInferencing) {
              CircularProgressIndicator(
                modifier = Modifier.padding(end = 8.dp).size(18.dp),
                strokeWidth = 2.dp,
              )
            } else {
              Icon(
                Icons.Rounded.PhotoCamera,
                contentDescription = null,
                modifier = Modifier.padding(end = 8.dp).size(18.dp),
              )
            }
            Text(if (isInferencing) "推論中" else "現フレームを推論")
          }
          Text(
            text = statusText,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }

      Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
      ) {
        Column(
          modifier = Modifier.padding(16.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Text(
              text = "Result",
              style = MaterialTheme.typography.titleMedium,
              fontWeight = FontWeight.SemiBold,
              modifier = Modifier.weight(1f),
            )
            if (isAnyInferencing) {
              CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
              )
            }
          }
          Text(
            text = resultStatusText,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          Text(
            text = resultDisplayText.ifBlank { "推論結果はここに表示されます。" },
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Default),
          )
        }
      }
      Spacer(Modifier.height(16.dp))
    }
  }
}

private data class CameraVlmTarget(val model: Model)

private fun findCameraVlmTarget(modelManagerViewModel: ModelManagerViewModel): CameraVlmTarget? {
  val task = modelManagerViewModel.getTaskById(BuiltInTaskId.LLM_ASK_IMAGE) ?: return null
  val downloadedNames = modelManagerViewModel.getAllDownloadedModels().map { it.name }.toSet()
  val model =
    task.models.firstOrNull { model ->
      model.llmSupportImage && downloadedNames.contains(model.name)
    }
  return model?.let { CameraVlmTarget(model = it) }
}

private fun runCurrentFrameInference(
  target: CameraVlmTarget,
  frame: TimestampedFrame,
  prompt: String,
  onStatus: (String) -> Unit,
  onResult: (String) -> Unit,
  onFinished: () -> Unit,
  launchOnMain: (() -> Unit) -> Unit,
) {
  val completed = AtomicBoolean(false)
  val resultBuilder = StringBuilder()

  fun finish(status: String? = null) {
    if (completed.compareAndSet(false, true)) {
      frame.bitmap.recycle()
      launchOnMain {
        status?.let { onStatus(it) }
        onFinished()
      }
    }
  }

  if (target.model.instance != null) {
    launchOnMain {
      onStatus("推論中: ${frame.bitmap.width}x${frame.bitmap.height}, ${frame.timestampMs}")
    }
    target.model.runtimeHelper.runInference(
      model = target.model,
      input = prompt,
      images = listOf(frame.bitmap),
      resultListener = { partial, done, _ ->
        if (partial.isNotEmpty()) {
          resultBuilder.append(partial)
          val snapshot = resultBuilder.toString()
          launchOnMain { onResult(snapshot) }
        }
        if (done) {
          finish("推論完了")
        }
      },
      cleanUpListener = { finish("推論完了") },
      onError = { message ->
        launchOnMain { onResult(message.ifBlank { "推論に失敗しました。" }) }
        finish("推論エラー")
      },
    )
    return
  }

  launchOnMain { onResult("画像対応モデルが未ロードです。Ask Image などでモデルを初期化してから再実行してください。") }
  finish("モデル未ロード")
}
