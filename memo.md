
コンテナ起動
```bash 
bash docker/scripts/launch-android-dev.bash
```

アプリ起動
```bash
adb shell monkey -p com.google.aiedge.gallery 1
```

アプリ停止
```bash
adb shell am force-stop com.google.aiedge.gallery
```

ログ表示
```bash
adb logcat -s EdgeServer:V EdgeServerManager:V
```

サーバ通信確認
```bash
adb forward tcp:8888 tcp:8888
```

サーバの状態確認
```bash
curl http://127.0.0.1:8888/health && echo
```

サーバモデル確認
```bash
curl http://127.0.0.1:8888/v1/models && echo
```


日本語プロンプトで文字化けして回答してくれない問題
```bash
curl http://127.0.0.1:8888/v1/chat/completions \
-H "Content-Type: application/json; charset=utf-8" \
-d '{"model":"auto","messages":[{"role":"user","content":"日本語で自己紹介してください。"}]}'
```

プロンプトテキストファイルから推論
```python
python3 gemma4_txt.py
```

画像から推論．プロンプトはprompt.txtを使う．
```python
python3 gemma4_image.py
```
画像ファイルパスを指定するなら
```python
python3 gemma4_image.py ファイルパス
```

スマホカメラの最新フレームから推論する場合は、先に Android アプリで Camera VLM 画面を開いてカメラを起動する。
そのうえで PC 側から adb forward を張る。
```bash
adb forward tcp:8888 tcp:8888
```

curl で最新カメラフレーム推論を呼ぶ。
```bash
curl http://127.0.0.1:8888/v1/camera/chat/completions \
-H "Content-Type: application/json; charset=utf-8" \
-d '{"model":"auto","prompt":"今カメラに写っている状況を日本語で簡潔に説明してください。","system_instruction":"短く、事実だけを日本語で返してください。","capture_mode":"latest","max_tokens":128}'
```

PC 側 Python サンプルで呼ぶ。
```python
python3 gemma4_camera.py
```
プロンプトを指定するなら
```python
python3 gemma4_camera.py "机の上にある物を列挙してください"
```

`gemma4_camera.py` は、コマンドライン引数が無い場合に `prompt_to_camera.txt` を UTF-8 で読み込む。
別ファイルを使う場合は `CAMERA_PROMPT_FILE` で指定する。
```bash
CAMERA_PROMPT_FILE=./my_prompt.txt python3 gemma4_camera.py
```

カメラ推論の回答テキストは `answer_from_camera.txt` に保存する。
別ファイルへ保存する場合は `CAMERA_ANSWER_FILE` で指定する。
```bash
CAMERA_ANSWER_FILE=./my_answer.txt python3 gemma4_camera.py
```

`gemma4_camera.py` は `/v1/camera/chat/completions` に `return_image: true` を付ける。
レスポンスに `image_base64` が含まれる場合は、`image_from_phone/{timestamp_ms}.jpg` に保存する。
保存先を変える場合は `CAMERA_IMAGE_DIR` で指定する。
```bash
CAMERA_IMAGE_DIR=./captures python3 gemma4_camera.py
```

`return_image` を省略または `false` にした場合、カメラ API のレスポンスには画像フィールドは含まれない。
