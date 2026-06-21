# Pixel 9 モバイルVLM端末化ロードマップ Phase 0 調査メモ

## 調査範囲

このメモは `/home/guch1/ssd_yamaguchi/gallery` の現ブランチ `pr-647` で、`TASK_PLAN.md` の Phase 0 としてローカルファイルおよびローカル git ref だけを確認した結果である。外部ネットワークは使っていない。

確認済みの現ブランチ主要ファイル:

- `Android/src/app/src/main/java/com/google/ai/edge/gallery/edgeserver/EdgeServer.kt`
- `Android/src/app/src/main/java/com/google/ai/edge/gallery/edgeserver/EdgeServerManager.kt`
- `Android/src/app/src/main/java/com/google/ai/edge/gallery/edgeserver/EdgeServerService.kt`
- `Android/src/app/src/main/java/com/google/ai/edge/gallery/edgeserver/EdgeServerScreen.kt`
- `Android/src/app/src/main/java/com/google/ai/edge/gallery/edgeserver/README.md`
- `Android/src/app/src/main/java/com/google/ai/edge/gallery/ui/common/LiveCameraView.kt`
- `Android/src/app/src/main/java/com/google/ai/edge/gallery/runtime/LlmModelHelper.kt`
- `Android/src/app/src/main/java/com/google/ai/edge/gallery/ui/llmchat/LlmChatModelHelper.kt`
- `Android/src/app/src/main/AndroidManifest.xml`
- `Android/src/app/src/main/java/com/google/ai/edge/gallery/ui/modelmanager/ModelManagerViewModel.kt`
- `Android/src/app/src/main/java/com/google/ai/edge/gallery/ui/navigation/GalleryNavGraph.kt`

確認済みの参考 ref:

- `pr/914`
- `pr/927`
- `pr/794`
- `pr/893`
- `xiaoyao/support/as-server`

`techjarves/mobile-server` と `Open-Distributed-Edge-Agents/EdgeGenAI` は、今回ユーザーが指定したローカル ref 一覧に存在しなかったため未確認。

## 現状整理

### EdgeServer は Phase 2/3 相当をかなり満たしている

`Android/src/app/src/main/java/com/google/ai/edge/gallery/edgeserver/EdgeServer.kt` は `NanoHTTPD` を継承し、OpenAI 互換寄りの HTTP API を提供している。確認済みエンドポイントは以下。

- `GET /health`: `{"status":"ok","model_loaded":...,"model":"..."}` を返す。
- `GET /v1/models`: `{"object":"list","data":[...]}` 形式で、現在 bound されているモデルを返す。
- `POST /v1/chat/completions`: OpenAI Chat Completions 風の `messages` 配列を受け、同期レスポンスと `stream: true` の SSE を処理する。
- `OPTIONS`: CORS 用に 200 を返す。

`EdgeServerScreen.kt` でも UI 上のエンドポイント表示として `GET /health`、`GET /v1/models`、`POST /v1/chat/completions` が確認できる。`EdgeServerService.kt` は Foreground Service として `EdgeServer` を起動し、`EdgeServerManager.kt` が singleton としてサービス接続、モデル bind、sampling 設定、最新 request/response 状態を保持する。

`POST /v1/chat/completions` のリクエスト処理は `handleChatCompletions()` が担当する。`messages` は `buildPromptPayload()` で Gemma 形式の prompt に変換され、content は以下の形を確認済み。

- 文字列 content
- 配列 content: `{"type":"text","text":"..."}` と `{"type":"image_url","image_url":{"url":"..."}}`
- オブジェクト content: `text` field または `image_url`

base64 画像のデコード箇所は `EdgeServer.kt` の `decodeImagePart()` と `decodeBitmapFromImageUrl()`。`data:*;base64,...` または base64 文字列を `android.util.Base64.decode()` し、`BitmapFactory.decodeByteArray()` で `Bitmap` に戻す。`http://` / `https://` の画像 URL は現状 `null` を返すため、ネットワーク画像取得は行わない。

推論呼び出しは `handleStreamingResponse()` と `handleNonStreamingResponse()` の両方で `helper.runInference(...)` を呼ぶ。ここで `images = payload.images`、`extraContext = mapOf("enable_thinking" to "true")` などを渡す。`applyRequestConfig()` は `max_tokens`、`top_k`、`top_p`、`temperature`、`ENABLE_THINKING` を一時的に `model.configValues` へ反映し、毎リクエスト前後に `helper.resetConversation(...)` を呼んで会話状態をリセットする。

同時推論制御は `EdgeServer.kt` の `private val inferenceSemaphore = Semaphore(1, true)` で実装済み。streaming / non-streaming のどちらも `tryAcquire(5, TimeUnit.SECONDS)` を行い、取れない場合は `429 Server busy. Try again later.` を返す。Phase 4 の camera endpoint では、既存互換を保つならこの共通推論ゲートを再利用するか、`VlmInferenceController` が同等の単一実行制御を持つ必要がある。TASK_PLAN.md の初期 API 案では推論中に HTTP 409 `inference_in_progress` を返す指定なので、新 endpoint だけは 409 に寄せる。

`EdgeServerManager.kt` は `bindModel()` で `Model` と `LlmModelHelper` を保持し、`supportImage` / `supportAudio` / `supportThinking` も記録する。`ModelManagerViewModel.kt` の `getPreferredEdgeServerTaskForModel()` は `LLM_ASK_IMAGE`、`LLM_ASK_AUDIO`、`LLM_CHAT` の順で Edge Server 用 task を選ぶため、画像対応モデルでは Ask Image 経路で bind される設計が確認できた。`GalleryNavGraph.kt` には `modelFinderCallback` があり、起動後に最初の downloaded model を自動初期化して EdgeServer に bind する導線もある。

Manifest では `CAMERA`、`INTERNET`、Foreground Service 系権限と、`android:name=".edgeserver.EdgeServerService"` の service 宣言を確認済み。

### LiveCameraView の流用可能性

`Android/src/app/src/main/java/com/google/ai/edge/gallery/ui/common/LiveCameraView.kt` は CameraX の `ImageAnalysis` を使う Compose view である。確認済みの設計:

- `ProcessCameraProvider.awaitInstance(context)` で camera provider を取得する。
- `ResolutionSelector` と `ResolutionStrategy(Size(preferredSize, preferredSize), FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)` を使う。
- `ImageAnalysis.Builder()` で `setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)` を既定にする。
- `setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)` を使う。
- analyzer は `Executors.newSingleThreadExecutor()` 上で `imageProxy.toBitmap()`、回転補正、front camera の左右反転を行う。
- `onBitmap(bitmap, imageProxy)` を呼び、`ImageProxy.close()` は caller の責務としてコメントされている。

このため「最新フレーム保持 -> VLM 推論」への流用は可能。ただし不足点もある。

- `LiveCameraView` 自体は Compose lifecycle に紐づく UI component で、EdgeServer から直接参照できる singleton provider ではない。
- 現状は caller が `ImageProxy.close()` を必ず呼ぶ契約で、provider 化するなら `try/finally` で close を保証する必要がある。
- bitmap の最新1枚保持、古い bitmap の recycle、640px 程度へのリサイズ、スレッドセーフな `sampleLatest()` は現ブランチには専用実装がない。
- `renderPreview = false` でも analyzer は動かせるが、サーバ起点だけでカメラを生かすには CameraX lifecycle owner と権限状態をどこで持つかを決める必要がある。

### VLM 推論経路

`Android/src/app/src/main/java/com/google/ai/edge/gallery/runtime/LlmModelHelper.kt` は共通 interface で、`runInference()` が `images: List<Bitmap>` を受け取る。`initialize()` と `resetConversation()` には `supportImage`、`supportAudio`、`systemInstruction`、`tools` がある。

`Android/src/app/src/main/java/com/google/ai/edge/gallery/ui/llmchat/LlmChatModelHelper.kt` では、`runInference()` が `images` を `Content.ImageBytes(image.toPngByteArray())` に変換し、text と合わせて `conversation.sendMessageAsync(Contents.of(contents), ...)` に渡す。画像対応でない session へ画像が来た場合は `This model session was initialized without image support.` を返す。したがって camera endpoint は新しい推論実装を作らず、既存 `LlmModelHelper.runInference(images = listOf(latestBitmap))` に集約するのが最小侵襲である。

## 参考 PR / fork 調査メモ

### `pr/914`: Live Video custom task

確認済みファイル:

- `Android/src/app/src/main/java/com/google/ai/edge/gallery/customtasks/livevideo/FrameBuffer.kt`
- `Android/src/app/src/main/java/com/google/ai/edge/gallery/customtasks/livevideo/CameraController.kt`
- `Android/src/app/src/main/java/com/google/ai/edge/gallery/customtasks/livevideo/LiveVideoViewModel.kt`
- `Android/src/app/src/main/java/com/google/ai/edge/gallery/customtasks/livevideo/LiveVideoScreen.kt`
- `Android/src/app/src/main/java/com/google/ai/edge/gallery/customtasks/livevideo/LiveVideoTask.kt`
- `Android/src/app/src/main/java/com/google/ai/edge/gallery/customtasks/livevideo/LiveVideoConfigs.kt`
- `Android/src/app/src/main/java/com/google/ai/edge/gallery/customtasks/livevideo/README.md`

特に使える点:

- `FrameBuffer.kt` は `TimestampedFrame(val bitmap: Bitmap, val timestampMs: Long)` と `FrameBuffer(maxFrames: Int = 32)` を持つ。`addFrame()`、`sample(n)`、`sampleLatest()`、`frameCount()`、`clear()` がすべて `@Synchronized`。`sampleLatest()` は最新 frame の `Bitmap.Config.ARGB_8888` copy を返すため、producer 側 recycle の影響を避けられる。
- `LiveVideoViewModel.kt` の `onCameraFrame(bitmap: Bitmap)` は一定間隔で frame を受け入れ、`frameBuffer.addFrame(bitmap)` と preview 用 copy を行う。`runInference()` は mode に応じて `frameBuffer.sampleLatest()` または複数 frame sampling を使い、推論中は `uiState.isProcessing` で二重実行を避ける。
- `LiveVideoScreen.kt` は `LiveCameraView(onBitmap = { bitmap, imageProxy -> viewModel.onCameraFrame(bitmap); imageProxy.close() }, cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA, preferredSize = SL280_PX, renderPreview = false)` を使う。`ImageProxy.close()` を caller 側で確実に呼ぶ実例として使える。
- `LiveVideoConfigs.kt` は `SL70_PX = 384`、`SL280_PX = 768` を定義し、用途ごとの `imageMaxPx` と temperature を分けている。TASK_PLAN.md の「640px 程度」はこの発想に近く、Phase 1/4 では 640px 前後の固定リサイズから始める。
- `CameraController.kt` は JSON から `CameraAction` を解釈し、auto capture / zoom / mode を扱う。Phase 8 以降の robot / action 連携には参考になるが、Phase 1/4 の最新フレーム API には過剰。

採用判断: `pr/914` 全体は Live Video UI、GemmaGaze、音声、TTS、smart camera を含み大きい。Phase 1/4 では `FrameBuffer.sampleLatest()` と `LiveVideoScreen` の `LiveCameraView` 利用パターンを参考に、最新1枚だけ保持する最小 `CameraFrameProvider` を作る。

### `pr/927`: system instruction support

確認済みファイル:

- `Android/src/app/src/main/java/com/google/ai/edge/gallery/common/SystemPromptHelper.kt`
- `Android/src/app/src/main/java/com/google/ai/edge/gallery/data/SystemPromptRepository.kt`
- `Android/src/app/src/main/java/com/google/ai/edge/gallery/runtime/aicore/AICoreModelHelper.kt`
- `Android/src/app/src/main/java/com/google/ai/edge/gallery/ui/llmchat/LlmChatTaskModule.kt`

要点:

- `SystemPromptRepository` は task id ごとに `system_prompt_$taskId` を DataStore の `secrets` に保存する。
- `SystemPromptHelper.getEffectiveSystemPrompt()` は custom prompt があればそれを、なければ `task.defaultSystemPrompt` を返す。
- `AICoreModelHelper` は instance に `systemInstruction` を保持し、`resetConversation()` で差し替える。
- `LlmChatTaskModule.kt` は LLM Chat / Ask Image / Ask Audio の `initializeModelFn()` に `systemInstruction` を渡し、UI で system prompt 編集を許可する。

Phase 5 用の参考。Phase 4 の camera endpoint では request の `system_instruction` を `Contents.of(Content.Text(...))` に変換して `resetConversation()` へ渡す設計が自然。ただし現ブランチの `EdgeServer.kt` は OpenAI messages 内の `system` role を `user` role に正規化して prompt に埋めており、`systemInstruction` としては使っていない。ここは将来修正対象。

### `pr/794`: performance mode

確認済みファイル:

- `Android/src/app/src/main/java/com/google/ai/edge/gallery/MainActivity.kt`
- `Android/src/app/src/main/java/com/google/ai/edge/gallery/ui/benchmark/BenchmarkViewModel.kt`
- `Android/src/app/src/main/java/com/google/ai/edge/gallery/data/Config.kt`
- `Android/src/app/src/main/java/com/google/ai/edge/gallery/ui/llmchat/LlmChatModelHelper.kt`

要点:

- `MainActivity.kt` は Android N 以上で `window.setSustainedPerformanceMode(true)` を呼ぶ。
- `BenchmarkViewModel.kt` は LiteRT LM の `setPerformanceMode(PerformanceMode.SUSTAINED_PERFORMANCE)` を benchmark 中に設定し、finally で `PerformanceMode.BALANCED` へ戻す。
- `Config.kt` / `LlmChatModelHelper.kt` は `max_tokens`、`topK`、`topP`、`temperature`、accelerator、vision accelerator、NPU/TPU 時の sampler 制限などの設定に関わる。

Phase 6 用の参考。camera endpoint 初期実装では performance mode を常時変更せず、まず画像リサイズ、max tokens、同時推論拒否、推論時間ログで測定可能にする。必要になったら benchmark と同じくスコープ付きで sustained performance を使い、finally で戻す。

### `pr/893`: MediaTek NPU runtime

確認済みファイル:

- `Android/src/app/src/main/java/com/google/ai/edge/gallery/data/ModelAllowlist.kt`
- `Android/src/app/src/main/java/com/google/ai/edge/gallery/data/Config.kt`
- `Android/src/app/src/main/java/com/google/ai/edge/gallery/data/Types.kt`
- `Android/src/app/src/main/java/com/google/ai/edge/gallery/ui/llmchat/LlmChatModelHelper.kt`

要点:

- `ModelAllowlist.kt` は allowlist の `accelerators` と `visionAccelerator` から `Accelerator.NPU` / `Accelerator.TPU` を扱う。Pixel device では description と accelerator 表示の NPU を TPU に置換する処理がある。
- `createLlmChatConfigsForNpuModel()` は NPU only モデルでは topK/topP/temperature を設定しない。コメントで「NPU models don't support setting topK, topP, and temperature」と明記されている。
- `LlmChatModelHelper.kt` は `Backend.NPU(nativeLibraryDir = context.applicationInfo.nativeLibraryDir)` を使う経路を持つ。

Phase 6 以降の backend / accelerator 切替用の参考。Pixel 9 で直接 MediaTek NPU runtime を使う前提は未確認なので、Phase 4 では現ブランチの既存 runtime selection を尊重する。

### `xiaoyao/support/as-server`

確認済みファイル:

- `as_server/README.md`
- `Android/src/app/src/main/java/com/google/ai/edge/gallery/server/OpenAIApiServer.kt`
- `Android/src/app/src/main/java/com/google/ai/edge/gallery/server/LlmModelHelperExt.kt`
- `Android/src/app/src/main/java/com/google/ai/edge/gallery/server/dto/OpenAIRequest.kt`
- `Android/src/app/src/main/java/com/google/ai/edge/gallery/service/ApiServerService.kt`

現ブランチ EdgeServer と比較して追加で参考になる点:

- Ktor CIO と kotlinx serialization DTO で `ChatCompletionRequest` / `ChatCompletionResponse` を型付きにしている。現ブランチは `Gson` / `JsonObject` ベースなので、短期は現方式維持、将来 API が増えるなら DTO 化が参考になる。
- `OpenAIApiServer.kt` は `Mutex` の `inferenceMutex.withLock` で推論を直列化する。現ブランチは `Semaphore(1, true)` なので方式は違うが目的は同じ。
- `image_url.detail` の `low` / `auto` / `high` に応じ、`LOW: 512px / 512^2`、`AUTO: 1024px / 1024^2`、`HIGH: no limit` の `resizeIfNeeded()` を持つ。camera endpoint の 640px リサイズ方針に直接参考になる。
- `system` role を `systemInstruction = Contents.of(Content.Text(...))` に変換し、`LlmChatModelHelper.newConversation()` へ渡す実装がある。
- Bruno collection に streaming、base64 image、system instruction、temperature、thinking などの API サンプルがある。Phase 4 以降の手動確認ケース作成に流用できる。

採用判断: 現ブランチは既に EdgeServer を持つため、`xiaoyao/support/as-server` の server/service を置き換え導入する必要は薄い。DTO、system instruction、image detail resize、API サンプルだけを参考にする。

## 採用設計

### CameraFrameProvider

新規候補:

- `Android/src/app/src/main/java/com/google/ai/edge/gallery/camera/CameraFrameProvider.kt`

責務:

- 最新 `Bitmap` 1枚だけを保持する。
- 古いフレームを溜めない。
- CameraX `ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST` を使う。
- analyzer 内では `ImageProxy.close()` を `try/finally` で保証する。
- 入力 bitmap は長辺 640px 程度にリサイズし、`Bitmap.Config.ARGB_8888` の immutable copy と timestamp を保持する。
- `@Synchronized` または `AtomicReference<TimestampedFrame>` で thread-safe に `sampleLatest(): TimestampedFrame?` を提供する。
- 古い保持 bitmap は置き換え時に recycle する。ただし `sampleLatest()` で返す bitmap は copy とし、consumer が recycle できるようにする。

実装案は `pr/914` の `FrameBuffer.sampleLatest()` を最小化する。`FrameBuffer` は複数 frame buffer だが、Phase 4 は queue 禁止・最新 frame のみなので `ArrayDeque` は不要。型は以下のような最小形にする。

```kotlin
data class TimestampedFrame(val bitmap: Bitmap, val timestampMs: Long)

object CameraFrameProvider {
  @Volatile private var latestFrame: TimestampedFrame? = null

  @Synchronized
  fun update(bitmap: Bitmap, timestampMs: Long = System.currentTimeMillis()) {
    val resized = resizeToLongEdge(bitmap, 640)
    latestFrame?.bitmap?.recycle()
    latestFrame = TimestampedFrame(resized.copy(Bitmap.Config.ARGB_8888, false), timestampMs)
  }

  @Synchronized
  fun sampleLatest(): TimestampedFrame? {
    val frame = latestFrame ?: return null
    return TimestampedFrame(frame.bitmap.copy(Bitmap.Config.ARGB_8888, false), frame.timestampMs)
  }
}
```

singleton として EdgeServer から参照可能にする方針。ただし CameraX の lifecycle は singleton に閉じ込めず、Phase 1 では Compose 画面が `LiveCameraView` から provider を更新し、Phase 4 では EdgeServer が `CameraFrameProvider.sampleLatest()` を読む構造にする。サーバ単独起動時にもカメラを動かす必要が出た場合は、Foreground Service 側で lifecycle owner 相当を持つ設計を別 Phase で検討する。

### VlmInferenceController

新規候補:

- `Android/src/app/src/main/java/com/google/ai/edge/gallery/edgeserver/VlmInferenceController.kt`

責務:

- prompt、system instruction、sampling config、latest bitmap を受け取る。
- 同時推論を `Mutex` または `AtomicBoolean` で拒否する。
- EdgeServer 既存の `LlmModelHelper.runInference()` 呼び出し、`applyRequestConfig()`、`restoreModelConfig()` 相当の処理を重複させない。
- Phase 4 初期では推論中なら即 HTTP 409 `inference_in_progress` を返す。

現ブランチの `EdgeServer.kt` には既に `handleNonStreamingResponse()` / `handleStreamingResponse()` へ共通化できそうな処理がある。実装時はまず `EdgeServer.kt` 内の共通推論処理を小さく切り出し、新 endpoint から同じ関数を呼ぶ。大規模リファクタリングは避ける。

### `/v1/camera/chat/completions`

新規 endpoint:

- `POST /v1/camera/chat/completions`
- Phase 4 実装済み。`EdgeServer.kt` に最小追加し、`CameraFrameProvider.sampleLatest()` のコピー済み最新フレームを既存 VLM 推論経路へ渡す。

初期 request:

```json
{
  "model": "gemma-3n",
  "prompt": "今カメラに写っている状況を日本語で簡潔に説明して",
  "system_instruction": "あなたはロボット制御用の視覚認識モジュールです。短く、事実だけを返してください。",
  "capture_mode": "latest",
  "max_tokens": 128,
  "temperature": 0.2
}
```

初期 response:

```json
{
  "text": "机の上にノートPCと白いマグカップがあります。",
  "inference_time_ms": 2300,
  "timestamp_ms": 1780000000000,
  "image_width": 640,
  "image_height": 480,
  "model": "gemma-3n"
}
```

エラー:

- 推論中: HTTP 409

```json
{
  "error": "inference_in_progress",
  "message": "Another VLM inference is currently running."
}
```

- 最新 frame がない: HTTP 409 または 503。初期は 409 `camera_frame_unavailable` より 503 `camera_frame_unavailable` の方が状態表現として自然だが、TASK_PLAN.md では推論中のみ 409 指定なので、実装時に明記する。
- 画像非対応モデル: 400 または 503。既存 `LlmChatModelHelper` は `onError("This model session was initialized without image support.")` を返すので、endpoint 側では早めに `EdgeServerManager.supportsImage()` を確認して 400 にする。

OpenAI 互換性については、既存 `/v1/chat/completions` は OpenAI 形式を維持し、新 endpoint は TASK_PLAN.md の簡易形式を採用する。将来、`messages` 形式も受けられるようにする場合は追加互換として扱う。

### Phase 1 から Phase 4 の実装順

1. Phase 1: Android 単体の最新フレーム保持を作る。
   - `CameraFrameProvider` を追加。
   - 既存 `LiveCameraView` を使う小さな画面または既存画面内 hook で `CameraFrameProvider.update()` を呼ぶ。
   - UI ボタンで `sampleLatest()` を取り、既存 `LlmModelHelper.runInference(images = listOf(bitmap))` に渡す。
   - 推論中は UI ボタンを無効化し、二重推論しない。

2. Phase 2/3: 現ブランチでは既に EdgeServer が満たす。
   - `/health`、`/v1/models`、`/v1/chat/completions` は存在。
   - base64 image_url を `Bitmap` に decode し、VLM へ渡す経路も存在。
   - 今後は破壊せず、互換確認と docs 更新を中心にする。

3. Phase 4: camera endpoint を EdgeServer に最小追加する。
   - `EdgeServer.kt` の routing に `POST /v1/camera/chat/completions` を追加。
   - `CameraFrameProvider.sampleLatest()` で最新 frame を取得。
   - 既存モデル bind と `LlmModelHelper.runInference()` を再利用。
   - 推論中は HTTP 409 `inference_in_progress`。
   - response に `inference_time_ms`、`timestamp_ms`、`image_width`、`image_height`、`model` を含める。
   - 実装済み。最新 frame がない場合は HTTP 409 `camera_frame_unavailable`、画像非対応モデルは HTTP 400 を返す。

4. Phase 5 以降:
   - `pr/927` を参考に `system_instruction` を本物の `systemInstruction` として渡す。
   - `pr/794` を参考に performance mode、max tokens、画像サイズ、推論時間ログを調整する。
   - `pr/893` は accelerator / NPU / TPU の将来対応として扱い、Pixel 9 では現行 runtime selection を維持する。
   - `xiaoyao/support/as-server` の DTO / API sample / image detail resize を必要に応じて取り込む。

### 統合方針

- 既存 EdgeServer を置き換えない。
- 通信層は `EdgeServer.kt`、カメラ保持は `CameraFrameProvider`、推論排他と実行は `VlmInferenceController` または EdgeServer 内の小さな共通関数に分ける。
- `LlmChatModelHelper` や `LlmModelHelper` に camera endpoint 専用ロジックを入れない。
- 古いフレームを queue しない。推論中の新規要求は初期実装では拒否する。
- 画像 resize、ImageProxy close、Bitmap copy/recycle の責務を明記し、メモリリークと camera 詰まりを避ける。
- 既存 `/v1/chat/completions` の request/response schema と streaming 挙動を壊さない。

## 未確認事項

- 実機 Pixel 9 での camera lifecycle と EdgeServer service の同時動作は未確認。
- `/v1/chat/completions` の実機 base64 画像推論は今回再実行していない。コード上の経路のみ確認済み。
- `techjarves/mobile-server` と `Open-Distributed-Edge-Agents/EdgeGenAI` はローカル ref が提示されていないため未確認。
- `pr/914` の `GemmaGazeInterpreter.kt` と `CameraEffects.kt` は今回の Phase 1/4 の最小設計には不要と判断し、詳細挙動までは未確認。
