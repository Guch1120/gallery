# Flutter Llama Gallery App

Google AI Edge Gallery の既存 Android 実装を壊さずに、GGUF ベースのローカル推論を Flutter でも扱えるようにするための新規アプリです。

現時点のスコープ:

- `llama_cpp_dart` を `path` 依存で利用
- ローカル `.gguf` モデルの手動パス指定
- モデル保存フォルダの選択と `.gguf` 自動検出
- チャット履歴を使った逐次生成
- `n_ctx` / `n_predict` / `temperature` / `top_k` / `top_p` の最小設定
- 既存 `Android/src` には非干渉

## ディレクトリ方針

- Flutter アプリ本体: `flutter_llama_gallery_app/`
- 既存 Gallery 本体: `Android/src/`
- Flutter 開発用 Docker: `docker/Dockerfile.flutter`, `docker/docker-compose.flutter.yml`

## 起動手順

1. Flutter 開発コンテナを起動します。

```bash
docker compose -f docker/docker-compose.flutter.yml up -d --build
```

2. コンテナに入ります。

```bash
docker compose -f docker/docker-compose.flutter.yml exec flutter-dev bash
```

3. 初回のみ、Flutter のプラットフォーム雛形を生成します。

```bash
cd /workspace/flutter_llama_gallery_app
./tool/bootstrap_project.sh
```

4. 依存関係を取得します。

```bash
cd /workspace/flutter_llama_gallery_app
flutter pub get
```

5. 接続済み端末を確認します。

```bash
flutter devices
```

6. Android 実機で起動します。

```bash
flutter run
```

## macOS での iOS ビルド手順

`flutter_llama_gallery_app/` は、共通アプリコードを `lib/` に、iOS 固有コードを `ios/` に分ける Flutter 標準構成になっています。
このため、Android と iOS でアプリ本体を二重実装する必要はありません。

現時点で、iOS 向けには以下まで準備済みです。

- `ios/` プロジェクト雛形の生成
- `llama_cpp_dart` の iOS 要件に合わせた deployment target `16.4` への調整
- フォルダ選択と `.gguf` 列挙のための iOS ネイティブ MethodChannel 追加
- Dart / Android 側の整合確認

ただし、最終的な iOS 実機ビルド確認だけは macOS + Xcode 上で行う必要があります。

### 前提

- macOS
- Xcode インストール済み
- Xcode Command Line Tools インストール済み
- CocoaPods インストール済み
- Flutter SDK インストール済み
- iOS 16.4 以上の実機、または同等の Simulator
- Apple Developer の署名設定が可能なこと

### 1. リポジトリ直下へ移動

```bash
cd /path/to/gallery
```

### 2. Flutter アプリのディレクトリへ移動

```bash
cd flutter_llama_gallery_app
```

### 3. 初回のみ、Flutter の状態確認

```bash
flutter doctor -v
```

### 4. 依存関係を取得

```bash
flutter pub get
```

### 5. iOS 側の Flutter 設定ファイルを生成

```bash
flutter precache --ios
```

### 6. CocoaPods 依存関係を解決

実行ディレクトリは `flutter_llama_gallery_app/` のままです。

```bash
cd ios
pod install
cd ..
```

### 7. Xcode ワークスペースを開く

実行ディレクトリは `flutter_llama_gallery_app/` です。

```bash
open ios/Runner.xcworkspace
```

### 8. Xcode で最低限確認する項目

- Target `Runner`
- Signing & Capabilities で Team を設定
- Bundle Identifier を必要に応じて調整
- iOS Deployment Target が `16.4` 以上であること
- 接続先の実機または Simulator を選択

### 9. コマンドラインでビルドだけ確認したい場合

実行ディレクトリは `flutter_llama_gallery_app/` です。

```bash
flutter build ios --simulator
```

実機向けに署名付きで進めるなら:

```bash
flutter build ipa
```

### 10. 実機で起動する場合

実行ディレクトリは `flutter_llama_gallery_app/` です。

```bash
flutter run
```

### 11. iOS での確認ポイント

- アプリ起動後にフォルダ選択が開くこと
- Files 上で `.gguf` を置いてあるフォルダを選べること
- 選択フォルダ配下の `.gguf` が一覧に出ること
- モデルをコピーせず、そのまま読み込めること
- チャット送信後に推論が返ること

## 使い方

1. `Pick folder` で `.gguf` を保存しているフォルダを選択します。
2. アプリは選択フォルダ配下を再帰的に走査し、検出した `.gguf` を一覧表示します。
3. 使いたい `.gguf` を選択します。
4. 必要なら `Native library path` を入力します。
   Android では通常空欄のままで構いません。
5. 各種推論パラメータを調整し、`Load model` を押します。
6. メッセージを入力して `Send` を押します。

この実装では、モデルをアプリ専用領域へコピーせず、選択したフォルダ内の既存 `.gguf` を直接使います。

## 注意点

- まずはテキストチャットに限定しています。画像入力や音声入力、モデルダウンロード UI はまだ移植していません。
- `llama_cpp_dart` 側の Android/iOS ネイティブビルドが通ることが前提です。
- 既存 Gallery の LiteRT-LM 実装とは別系統で、こちらは `llama.cpp` / GGUF を前提にしています。
- iOS 側の deployment target は `16.4` に合わせています。これは `llama_cpp_dart` の Podspec 要件に合わせたものです。
- iOS 実機ビルドは Linux / Docker では完了できません。最終確認は macOS + Xcode が必要です。
