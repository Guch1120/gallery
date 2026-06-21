# Pixel 9 モバイルVLM端末化ロードマップ実装依頼
## 目的
Google AI Edge Gallery のフォーク `Guch1120/gallery` をベースに、Pixel 9 を「PCからHTTP/WebSocket等で呼び出せるモバイルVLM端末」として扱えるように段階的に実装したい。最短実装ではなく、既存機能・本家PR・有用フォークの実装を調査・活用しながら、将来的にロボット制御やFunction Callingにも拡張しやすい構成でステップバイステップに進めることを重視する。
現在すでに、PC側Pythonから画像およびテキストをAndroid側へ渡してVLM推論リクエストする導線は `Guch1120/gallery` 上で成功している。次の目標は、PCから任意タイミング、または将来的にはリアルタイムに、Pixel 9 のスマホカメラ画像を取得し、その画像に対してVLMで状況推論を行い、結果をPCへ返すことである。

## 重要な前提
実装を急がず、ロードマップ方式で段階的に進める。既存のGallery機能、特に Ask Image / LlmChat / LiveCameraView / Mobile Actions / Function Calling の導線をなるべく活用する。大規模な独自実装で置き換えるのではなく、既存構造に沿って拡張する。推論中は次の推論を開始しない設計にする。VLM推論は重いため、同時推論や推論キューの多重蓄積は避ける。基本方針は「常に最新フレームだけを使う」「推論中に次要求が来た場合は拒否・待機・最新要求へ置換のいずれかを明示的に扱う」とする。

## 参考にする本家PR
以下の本家PRは必ず調査し、必要に応じて取り込み・cherry-pick・手動移植・設計参考にすること。ただし、盲目的にマージせず、差分を確認して現在の `Guch1120/gallery` の実装と衝突しない形で取り込む。
- `google-ai-edge/gallery` PR #914 `Add a new Live Video custom task`: カメラ連続入力・ライブ映像処理に直結するため最重要。LiveCameraView、CameraX、ImageAnalysis、最新フレーム保持、ライブ推論導線の参考にする。
- PR #927 `Enable system instruction support for AICore models`: 「状況説明せよ」「ロボット制御向けに短く返せ」「JSON形式で返せ」など、システムプロンプト制御に有用。将来的にVLM端末として安定した応答形式を得るために参考にする。
- PR #794 `Introduce the performance mode`: リアルタイム性・推論速度・パフォーマンス設定に関係する可能性があるため、推論速度調整、低遅延モード、品質/速度トレードオフの参考にする。
- PR #893 `MediaTek NPU runtime`: Pixel 9向けに直接使えるとは限らないが、NPU/アクセラレータ実行系、delegate、runtime切替、バックエンド抽象化の参考にする。

## 参考にするフォーク
以下のフォークは、PC連携・Android側サーバ化・OpenAI互換API化の参考元として調査すること。
- `techjarves/mobile-server`: Android端末をOpenAI互換のローカルAIサーバにする方向性の実装。Ktor server、Foreground Service、`/v1/models`、`/v1/chat/completions`、トンネル機能などを参考にする。ただし最初から外部公開やNgrok/Cloudflare Tunnelは不要。
- `xiaoyao9184/gallery` の `support/as-server` ブランチ: Androidアプリ内にKtor HTTP serverを立て、OpenAI互換APIとして `/v1/models` と `/v1/chat/completions` を提供する構成の参考にする。特に `as_server/README.md`、server/service/dto/uiまわりの構成を確認する。
- `Open-Distributed-Edge-Agents/EdgeGenAI`: 複数Android端末や分散エッジエージェント構成、Nearby Connectionsなどの将来拡張の参考にする。ただし今回の初期実装では優先度は低い。
- その他、最近更新順で活発なforkがあれば、カメラ、VLM、server、OpenAI API、Function Calling、NPU、performance、live video、websocket、camera capture等のキーワードで有用差分を確認する。

## PC連携方式の方針
初期方針は「Android側をサーバにする方式」を優先する。つまり、PC側PythonがAndroid上のHTTP APIまたはWebSocket APIへ推論リクエストを投げ、Android側でカメラ画像取得とVLM推論を行い、結果をPCへ返す構成にする。理由は、Python、ROS、ロボット制御ノードから扱いやすく、OpenAI互換APIにも寄せやすく、参考フォークもこの方向に実装が進んでいるためである。
ただし、将来的に複数端末管理やNAT越え、常時接続型の制御が必要になった場合に備え、PC側サーバ方式やWebSocketクライアント方式に拡張できるよう、通信層は推論ロジックと密結合させない。Androidの推論コア、カメラフレーム取得、HTTP/WebSocket APIは分離する。

## 推奨アーキテクチャ
構成は以下を目標にする。
PC Python client → Android HTTP/WebSocket server → CameraFrameProvider → VLM InferenceController → 既存LlmChatModelHelper / Ask Image推論 → JSON/SSE/WebSocketで結果返却。
Android側には以下の責務を分けて実装する。
- `CameraFrameProvider`: CameraX / LiveCameraView / ImageAnalysis を使い、最新の `Bitmap` を保持する。古いフレームは溜めない。必要に応じて解像度を下げる。`ImageProxy.close()` の責務を明確にする。
- `VlmInferenceController`: prompt、画像、system instruction、model設定を受け取り、既存のVLM推論経路へ渡す。`isInferencing` 等で同時推論を防ぐ。
- `ApiServerService`: Foreground ServiceとしてHTTP serverを起動/停止する。初期はローカルLANまたはadb forwardで利用できればよい。
- `OpenAIApiServer` または `MobileVlmApiServer`: `/v1/models`、`/v1/chat/completions`、将来的に `/v1/camera/chat/completions` や `/camera/capture` を提供する。
- `PcClientExamples`: PC側Pythonから叩く最小サンプルを用意する。

## 実装ロードマップ
### Phase 0: 現状把握と差分調査
`Guch1120/gallery` の現在のブランチ、既存のPC→Android推論リクエスト実装、Ask Image、LlmChat、ModelHelper、CameraX、LiveCameraView、Mobile Actions、Function Callingまわりを確認する。本家 `google-ai-edge/gallery` の最新mainとの差分を確認する。PR #914、#927、#794、#893 の差分を確認し、取り込む価値・衝突リスク・依存関係をメモする。`techjarves/mobile-server` と `xiaoyao9184/gallery:support/as-server` の構成を確認し、HTTP server、Foreground Service、DTO、OpenAI互換API設計を参考として整理する。このPhaseでは大きな実装変更は行わず、`docs/mobile_vlm_roadmap.md` のような設計メモを作る。
完了条件: 参考PR・参考フォーク・現在実装との差分・採用する設計方針がドキュメント化されていること。

### Phase 1: Android単体で「現在カメラ画像→VLM推論」を成立させる
PC連携の前に、Androidアプリ単体でカメラプレビューまたは最新フレームを取得し、その1枚を既存VLM推論に渡して結果を表示できるようにする。既存の `LiveCameraView.kt` が利用可能なら活用する。なければ本家mainまたはPR #914から取り込む。最初はUIボタンで「現在フレームを推論」を実行する。推論中はボタンを無効化し、同時推論を禁止する。画像解像度は最初から高くしすぎず、必要ならリサイズしてVLMへ渡す。
完了条件: Android画面上でカメラ画像を表示し、ボタン押下で現在フレームに対するVLM推論結果が表示されること。同時推論が起きないこと。

### Phase 2: PCからテキスト推論をHTTPで呼べるサーバ基盤を作る
`techjarves/mobile-server` と `xiaoyao9184/gallery:support/as-server` を参考に、Android側にKtor serverまたは同等のHTTP serverを追加する。最初はカメラなしで `/v1/models` と `/v1/chat/completions` の最小実装を作る。既存のPC→Android推論実装がある場合は、それを整理・統合し、OpenAI互換に寄せられる部分は寄せる。Foreground Serviceとしてサーバを起動し、アプリUIからサーバの起動/停止、ポート番号、接続先URLを確認できるようにする。最初は同一LANまたは `adb forward tcp:8080 tcp:8080` で動作確認する。外部公開やトンネル機能はこの段階では不要。
完了条件: PC Pythonの `requests` からAndroidの `/v1/models` と `/v1/chat/completions` を呼び、テキスト推論結果が返ること。

### Phase 3: PCから画像+テキスト推論をHTTPで呼ぶ
現在成功しているPC→Androidの画像+テキスト推論リクエストを、Phase 2のHTTP API基盤に統合する。OpenAI互換の `messages` 形式、または独自の簡易形式のどちらでもよいが、将来的にはOpenAI互換に寄せる。画像はbase64、multipart、またはローカルURLのいずれかを検討する。最初はbase64でよい。既存の `LlmChatModelHelper` など、BitmapをVLM入力へ変換する既存処理を再利用する。
完了条件: PC Pythonから任意画像とpromptをPOSTし、Android側VLMで推論した結果が返ること。既存の成功済み機能が新API構成でも再現できること。

### Phase 4: PCから「スマホカメラの現在フレーム」を推論できるAPIを作る
ここが今回の中心機能。PC PythonからAndroidへHTTPリクエストを送り、Android側で保持している最新カメラフレームをVLMへ渡して推論し、結果を返すAPIを作る。API名は暫定で `/v1/camera/chat/completions` または `/camera/capture-and-infer` とする。リクエストには `prompt`、`system_instruction`、`max_tokens`、`temperature`、`model`、`capture_mode` などを含められるようにする。初期は `capture_mode = latest` のみでよい。推論中に次のリクエストが来た場合は、HTTP 409 `inference_in_progress` を返す、またはオプションで待機する。キューに大量に溜める実装は禁止する。
完了条件: PC Pythonから「今カメラに写っているものを説明して」と投げると、Pixel 9の現在カメラ画像を使ったVLM推論結果が返ること。推論中の重複リクエストが安全に扱われること。

### Phase 5: システムプロンプト・応答形式・ロボット向け短文応答を整える
PR #927を参考に、system instructionを扱えるようにする。用途としては、「状況説明せよ」「ロボット制御向けに短く返せ」「危険物があれば警告せよ」「JSONで返せ」「物体名・位置関係・信頼度だけ返せ」などを想定する。PC側APIからsystem instructionを指定できるようにする。デフォルトsystem promptも設定可能にする。
完了条件: PCからsystem instructionつきでカメラVLM推論を呼び、短文・JSON・ロボット向けなど指定した形式に近い結果が返ること。

### Phase 6: パフォーマンス設定と低遅延化
PR #794を参考に、推論速度と品質の調整を行う。カメラフレームのリサイズ、JPEG/PNG変換、Bitmapコピー回数、ImageProxy close、推論中フラグ、モデルロード状態、温度、max tokens、出力長制限を見直す。必要に応じて performance mode を導入する。VLM推論は毎フレーム実行しない。HTTPポーリングで1〜2Hz程度、または明示リクエスト時のみ実行する設計を基本にする。
完了条件: 推論中にアプリが固まらず、連続リクエストでもメモリリークやカメラ詰まりが起きにくいこと。レスポンス時間・画像サイズ・推論中状態がログで確認できること。

### Phase 7: WebSocket / ストリーミング対応
HTTPで安定した後、必要に応じてWebSocketを追加する。WebSocketではPCがAndroidに接続し、`infer_current_frame`、`set_prompt`、`set_system_instruction`、`start_periodic_inference`、`stop_periodic_inference` のような命令を送れるようにする。最初は推論結果だけを返せばよい。カメラ画像そのもののストリーミングは帯域・プライバシー・負荷が増えるため後回しにする。SSE streamingはテキスト生成結果の逐次返却に使えそうなら検討する。
完了条件: PC PythonのWebSocketクライアントからAndroidへ命令し、現在フレームのVLM推論結果を受け取れること。

### Phase 8: Function Calling / Mobile Actions化
現在の用途ではPCから明示的に推論要求を投げるが、将来の再利用性を考えてFunction Calling化も検討する。たとえば `capture_current_frame`、`describe_current_scene`、`detect_target_object`、`check_safety` のようなTool/Actionとして実装し、モデル判断で必要に応じてカメラ取得や状況説明を呼べるようにする。既存の Mobile Actions / Function Calling ガイドに沿い、独自のActionTypeとTool関数を追加する。最初はHTTP APIと内部実装を共有し、Function Calling用に別ロジックを重複実装しない。
完了条件: Androidアプリ内またはAPI経由で、Function Calling風に現在フレーム取得・状況説明を呼べる設計になっていること。少なくとも設計と最小PoCがあること。

### Phase 9: ロボット/ROS連携を見据えたAPI整備
将来的にROSノードやロボット制御から呼ぶことを想定し、レスポンス形式を安定化する。自然文だけでなく、必要に応じてJSONで `objects`、`scene_summary`、`hazards`、`target_found`、`relative_position`、`confidence`、`timestamp_ms`、`image_width`、`image_height` などを返せるようにする。PC側Pythonサンプルに加えて、ROS2ノードからHTTPで呼ぶ例も将来的に作れるようにする。
完了条件: ロボット制御側がパースしやすいJSON応答形式が用意されていること。

## 初期API案
まずは以下のAPIを目標にする。
`GET /v1/models`: 利用可能モデル一覧を返す。
`POST /v1/chat/completions`: 通常のテキスト、またはPCから送信された画像+テキストの推論。
`POST /v1/camera/chat/completions`: Android側カメラの最新フレームを使ったVLM推論。

リクエスト例:
{
  "model": "gemma-3n",
  "prompt": "今カメラに写っている状況を日本語で簡潔に説明して",
  "system_instruction": "あなたはロボット制御用の視覚認識モジュールです。短く、事実だけを返してください。",
  "capture_mode": "latest",
  "max_tokens": 128,
  "temperature": 0.2
}

レスポンス例:
{
  "text": "机の上にノートPCと白いマグカップがあります。",
  "inference_time_ms": 2300,
  "timestamp_ms": 1780000000000,
  "image_width": 640,
  "image_height": 480,
  "model": "gemma-3n"
}

推論中のレスポンス例:

{
  "error": "inference_in_progress",
  "message": "Another VLM inference is currently running."
}

## PC側Pythonサンプル
Phase 4完了時点で、以下のような最小Pythonサンプルを用意する。
import requests

url = "http://127.0.0.1:8080/v1/camera/chat/completions"

payload = {
    "model": "gemma-3n",
    "prompt": "今の状況を説明して",
    "system_instruction": "短く、事実だけを日本語で返してください。",
    "capture_mode": "latest",
    "max_tokens": 128
}

res = requests.post(url, json=payload, timeout=60)
print(res.status_code)
print(res.json())


ADB forwardで使う場合は、PC側で `adb forward tcp:8080 tcp:8080` を実行してから `http://127.0.0.1:8080` にアクセスできるようにする。同一LANの場合はPixel 9のIPアドレスを使う。

## 実装上の注意
VLM推論中は次の推論を開始しない。`Mutex`、`AtomicBoolean`、Coroutineの排他制御などを使い、同時推論を防ぐ。ImageAnalysisは `STRATEGY_KEEP_ONLY_LATEST` を使い、古いフレームを蓄積しない。`ImageProxy.close()` を確実に呼ぶ。Bitmapは必要ならコピーしてからImageProxyを閉じる。高解像度をそのままVLMに渡すと遅くなるため、初期は640px程度へのリサイズを検討する。カメラ権限、Foreground Service権限、ネットワーク権限を確認する。LAN公開する場合は最低限の認証トークンやローカル限定の注意書きを入れる。Ngrok/Cloudflare Tunnelなどの外部公開は初期実装では不要。ログには推論開始/終了、画像サイズ、推論時間、エラー、同時推論拒否を出す。

## ブランチ戦略
各Phaseごとに小さなブランチを切る。例: `feat/mobile-vlm-roadmap-docs`, `feat/live-camera-vlm-local`, `feat/android-api-server`, `feat/camera-vlm-http-api`, `feat/system-instruction-vlm`, `feat/mobile-vlm-performance`, `feat/mobile-vlm-function-calling`。PR #914、#927、#794、#893 を取り込む場合は、直接mainに混ぜず、それぞれ検証ブランチを作成して差分とビルド結果を確認する。

## 成果物

最低限、以下を作成・更新すること。
- `docs/mobile_vlm_roadmap.md`: 設計・調査・ロードマップ。
- Android側のカメラ最新フレーム取得コンポーネント。
- Android側のVLM推論制御コンポーネント。
- Android側HTTP server / Foreground Service。
- `/v1/models`、`/v1/chat/completions`、`/v1/camera/chat/completions` の最小API。
- PC側Pythonサンプル。
- READMEまたはdocsに、ADB forward、同一LAN接続、Pixel 9での実行手順を記述。
- 推論中重複リクエストの扱いを明記したテストまたは確認手順。

## 最終ゴール
Pixel 9を、PCやロボット制御PCから呼び出せるオンデバイスVLM端末として扱えるようにする。PCから「今見えている状況を説明して」「対象物があるか確認して」「ロボット制御向けに短くJSONで返して」といったリクエストを送り、Android側がスマホカメラの現在フレームを使って推論し、安定した形式で結果を返す状態を目指す。