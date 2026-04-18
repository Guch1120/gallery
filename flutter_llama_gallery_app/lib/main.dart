import 'dart:async';
import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:llama_cpp_dart/llama_cpp_dart.dart';

void main() {
  runApp(const FlutterLlamaGalleryApp());
}

class FlutterLlamaGalleryApp extends StatelessWidget {
  const FlutterLlamaGalleryApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Flutter Llama Gallery',
      theme: ThemeData(
        colorScheme: ColorScheme.fromSeed(
          seedColor: const Color(0xFF0F766E),
          brightness: Brightness.light,
        ),
        useMaterial3: true,
      ),
      home: const ChatScreen(),
    );
  }
}

enum PromptPreset {
  gemma('Gemma'),
  chatml('ChatML'),
  alpaca('Alpaca');

  const PromptPreset(this.label);

  final String label;

  ChatFormat get format {
    switch (this) {
      case PromptPreset.gemma:
        return ChatFormat.gemma;
      case PromptPreset.chatml:
        return ChatFormat.chatml;
      case PromptPreset.alpaca:
        return ChatFormat.alpaca;
    }
  }
}

enum MessageRole {
  system,
  user,
  assistant,
}

class ChatMessageItem {
  const ChatMessageItem({
    required this.role,
    required this.text,
  });

  final MessageRole role;
  final String text;

  ChatMessageItem copyWith({
    MessageRole? role,
    String? text,
  }) {
    return ChatMessageItem(
      role: role ?? this.role,
      text: text ?? this.text,
    );
  }
}

class DiscoveredModel {
  const DiscoveredModel({
    required this.displayName,
    required this.path,
    this.uri,
  });

  final String displayName;
  final String path;
  final String? uri;
}

class ChatScreen extends StatefulWidget {
  const ChatScreen({super.key});

  @override
  State<ChatScreen> createState() => _ChatScreenState();
}

class _ChatScreenState extends State<ChatScreen> {
  static const MethodChannel folderPickerChannel =
      MethodChannel('com.google.ai.edge.flutter_llama_gallery_app/gguf_folder_picker');

  final TextEditingController modelPathController = TextEditingController();
  final TextEditingController nativeLibraryPathController = TextEditingController();
  final TextEditingController systemPromptController = TextEditingController();
  final TextEditingController inputController = TextEditingController();
  final TextEditingController nCtxController = TextEditingController(text: '4096');
  final TextEditingController nPredictController = TextEditingController(text: '512');
  final TextEditingController temperatureController = TextEditingController(text: '0.7');
  final TextEditingController topKController = TextEditingController(text: '40');
  final TextEditingController topPController = TextEditingController(text: '0.9');
  final ScrollController scrollController = ScrollController();
  final String? defaultLibraryPath = Llama.libraryPath;

  LlamaParent? llamaParent;
  StreamSubscription<String>? textSubscription;
  StreamSubscription<CompletionEvent>? completionSubscription;

  PromptPreset selectedPreset = PromptPreset.gemma;
  final List<ChatMessageItem> messages = <ChatMessageItem>[];
  final List<DiscoveredModel> discoveredModels = <DiscoveredModel>[];
  String? selectedDirectoryPath;
  DiscoveredModel? selectedDiscoveredModel;

  bool isModelLoading = false;
  bool isModelReady = false;
  bool isGenerating = false;
  bool isScanningDirectory = false;
  String statusText = 'モデル未ロード';

  @override
  void dispose() {
    textSubscription?.cancel();
    completionSubscription?.cancel();
    unawaited(llamaParent?.dispose());

    modelPathController.dispose();
    nativeLibraryPathController.dispose();
    systemPromptController.dispose();
    inputController.dispose();
    nCtxController.dispose();
    nPredictController.dispose();
    temperatureController.dispose();
    topKController.dispose();
    topPController.dispose();
    scrollController.dispose();
    super.dispose();
  }

  Future<void> loadModel() async {
    final String typedModelPath = modelPathController.text.trim();
    String modelPath =
        selectedDiscoveredModel?.path.trim().isNotEmpty == true
            ? selectedDiscoveredModel!.path.trim()
            : typedModelPath;
    if (modelPath.isEmpty) {
      setStatus('モデルフォルダを選択するか、Model path を入力してください。');
      return;
    }

    final int? nCtx = int.tryParse(nCtxController.text.trim());
    final int? nPredict = int.tryParse(nPredictController.text.trim());
    final double? temperature = double.tryParse(temperatureController.text.trim());
    final int? topK = int.tryParse(topKController.text.trim());
    final double? topP = double.tryParse(topPController.text.trim());

    if (nCtx == null || nPredict == null || temperature == null || topK == null || topP == null) {
      setStatus('数値パラメータの形式が不正です。');
      return;
    }

    setState(() {
      isModelLoading = true;
      isModelReady = false;
      isGenerating = false;
      statusText = 'モデルを読み込み中...';
    });

    try {
      await unloadModel();

      final bool usesPreparedAndroidPath =
          Platform.isAndroid && selectedDiscoveredModel?.uri != null;

      if (usesPreparedAndroidPath) {
        final Map<String, dynamic>? payload = await folderPickerChannel
            .invokeMapMethod<String, dynamic>(
              'prepareGgufFileAccess',
              <String, dynamic>{'uri': selectedDiscoveredModel!.uri},
            );
        final String? accessPath = payload?['accessPath'] as String?;
        if (accessPath == null || accessPath.isEmpty) {
          throw Exception('Android のモデルアクセス準備に失敗しました。');
        }
        modelPath = accessPath;
      }

      final String nativeLibraryPath = nativeLibraryPathController.text.trim();
      Llama.libraryPath =
          nativeLibraryPath.isNotEmpty ? nativeLibraryPath : defaultLibraryPath;

      debugPrint('Loading model from: $modelPath');
      debugPrint('Uses prepared Android path: $usesPreparedAndroidPath');
      if (selectedDiscoveredModel?.uri != null) {
        debugPrint('Selected model uri: ${selectedDiscoveredModel!.uri}');
      }

      final ContextParams contextParams = ContextParams()
        ..nCtx = nCtx
        ..nPredict = nPredict;

      final SamplerParams samplerParams = SamplerParams()
        ..temp = temperature
        ..topK = topK
        ..topP = topP;

      final LlamaLoad loadCommand = LlamaLoad(
        path: modelPath,
        modelParams: ModelParams(),
        contextParams: contextParams,
        samplingParams: samplerParams,
      );

      final LlamaParent parent = LlamaParent(loadCommand);
      await parent.init();

      textSubscription = parent.stream.listen(handleChunk);
      completionSubscription = parent.completions.listen(handleCompletion);

      setState(() {
        llamaParent = parent;
        isModelLoading = false;
        isModelReady = true;
        statusText = 'モデルを読み込みました。';
      });
    } catch (error) {
      debugPrint('Model load failed: $error');
      setState(() {
        isModelLoading = false;
        isModelReady = false;
        statusText = '読み込み失敗: $error';
      });
    }
  }

  Future<void> unloadModel() async {
    await textSubscription?.cancel();
    await completionSubscription?.cancel();
    textSubscription = null;
    completionSubscription = null;

    final LlamaParent? parent = llamaParent;
    llamaParent = null;

    if (parent != null) {
      try {
        await parent.dispose();
      } catch (_) {}
    }

    if (Platform.isAndroid) {
      try {
        await folderPickerChannel.invokeMethod<void>('releaseAllPreparedGgufFileAccess');
      } catch (_) {}
    }

    if (mounted) {
      setState(() {
        isModelReady = false;
        isGenerating = false;
      });
    }
  }

  Future<void> resetConversation() async {
    setState(() {
      messages.clear();
      statusText = '会話をリセットしました。';
    });
  }

  Future<void> pickModelDirectory() async {
    try {
      if (Platform.isAndroid || Platform.isIOS) {
        final Map<String, dynamic>? payload = await folderPickerChannel
            .invokeMapMethod<String, dynamic>('pickGgufDirectory');

        if (payload == null) {
          setStatus('フォルダ選択をキャンセルしました。');
          return;
        }

        final String? directoryPath = payload['directoryPath'] as String?;
        final List<DiscoveredModel> models =
            ((payload['models'] as List<dynamic>?) ?? <dynamic>[])
                .map((dynamic rawModel) {
                  final Map<Object?, Object?> map = rawModel as Map<Object?, Object?>;
                  return DiscoveredModel(
                    displayName:
                        (map['displayName'] as String?) ??
                        ((map['path'] as String?)?.split(Platform.pathSeparator).last ??
                            'unknown.gguf'),
                    path: (map['path'] as String?) ?? '',
                    uri: map['uri'] as String?,
                  );
                })
                .where((DiscoveredModel model) => model.path.isNotEmpty || model.uri != null)
                .toList();

        if (directoryPath == null || directoryPath.isEmpty) {
          setStatus('フォルダ選択に失敗しました。');
          return;
        }

        setState(() {
          isScanningDirectory = false;
          selectedDirectoryPath = directoryPath;
          discoveredModels
            ..clear()
            ..addAll(models);
          selectedDiscoveredModel = models.isNotEmpty ? models.first : null;
          if (selectedDiscoveredModel != null) {
            modelPathController.text = selectedDiscoveredModel!.path;
          }
          statusText = models.isEmpty
              ? 'GGUF ファイルが見つかりませんでした。'
              : 'GGUF を ${models.length} 件検出しました。';
        });
        return;
      }

      setStatus('このプラットフォームではネイティブのフォルダ選択実装がありません。');
    } catch (error) {
      setStatus('フォルダ選択に失敗しました: $error');
    }
  }

  Future<void> scanModelDirectory(String directoryPath) async {
    setState(() {
      isScanningDirectory = true;
      selectedDirectoryPath = directoryPath;
      statusText = 'GGUF ファイルを検索中...';
    });

    try {
      final Directory directory = Directory(directoryPath);
      if (!await directory.exists()) {
        setStatus('選択したフォルダが見つかりません。');
        setState(() {
          isScanningDirectory = false;
        });
        return;
      }

      final List<String> ggufFiles = <String>[];
      await for (final FileSystemEntity entity
          in directory.list(recursive: true, followLinks: false)) {
        if (entity is! File) {
          continue;
        }
        if (!entity.path.toLowerCase().endsWith('.gguf')) {
          continue;
        }
        ggufFiles.add(entity.path);
      }

      ggufFiles.sort();

      setState(() {
        discoveredModels
          ..clear()
          ..addAll(
            ggufFiles.map(
              (String path) => DiscoveredModel(
                displayName: path.split(Platform.pathSeparator).last,
                path: path,
              ),
            ),
          );
        selectedDiscoveredModel =
            discoveredModels.isNotEmpty ? discoveredModels.first : null;
        if (selectedDiscoveredModel != null) {
          modelPathController.text = selectedDiscoveredModel!.path;
        }
        isScanningDirectory = false;
        statusText = ggufFiles.isEmpty
            ? 'GGUF ファイルが見つかりませんでした。'
            : 'GGUF を ${ggufFiles.length} 件検出しました。';
      });
    } catch (error) {
      setState(() {
        isScanningDirectory = false;
        statusText = 'フォルダの走査に失敗しました: $error';
      });
    }
  }

  Future<void> sendPrompt() async {
    final LlamaParent? parent = llamaParent;
    final String input = inputController.text.trim();

    if (parent == null || !isModelReady) {
      setStatus('先にモデルを読み込んでください。');
      return;
    }
    if (input.isEmpty || isGenerating) {
      return;
    }

    final List<ChatMessageItem> nextMessages = List<ChatMessageItem>.from(messages)
      ..add(ChatMessageItem(role: MessageRole.user, text: input))
      ..add(const ChatMessageItem(role: MessageRole.assistant, text: ''));

    setState(() {
      messages
        ..clear()
        ..addAll(nextMessages);
      isGenerating = true;
      statusText = '生成中...';
      inputController.clear();
    });
    scrollToBottom();

    final ChatHistory history = ChatHistory();
    final String systemPrompt = systemPromptController.text.trim();
    if (systemPrompt.isNotEmpty) {
      history.addMessage(role: Role.system, content: systemPrompt);
    }

    for (final ChatMessageItem message in nextMessages) {
      switch (message.role) {
        case MessageRole.system:
          history.addMessage(role: Role.system, content: message.text);
          break;
        case MessageRole.user:
          history.addMessage(role: Role.user, content: message.text);
          break;
        case MessageRole.assistant:
          history.addMessage(role: Role.assistant, content: message.text);
          break;
      }
    }

    final String prompt =
        history.exportFormat(selectedPreset.format, leaveLastAssistantOpen: true);

    try {
      await parent.sendPrompt(prompt);
    } catch (error) {
      setState(() {
        isGenerating = false;
        statusText = '送信失敗: $error';
      });
    }
  }

  Future<void> stopGeneration() async {
    final LlamaParent? parent = llamaParent;
    if (parent == null || !isGenerating) {
      return;
    }

    try {
      await parent.stop();
      setState(() {
        isGenerating = false;
        statusText = '生成を停止しました。';
      });
    } catch (error) {
      setStatus('停止失敗: $error');
    }
  }

  void handleChunk(String chunk) {
    if (!mounted || messages.isEmpty) {
      return;
    }

    final int lastIndex = messages.length - 1;
    final ChatMessageItem lastMessage = messages[lastIndex];
    if (lastMessage.role != MessageRole.assistant) {
      return;
    }

    setState(() {
      messages[lastIndex] = lastMessage.copyWith(text: lastMessage.text + chunk);
    });
    scrollToBottom();
  }

  void handleCompletion(CompletionEvent event) {
    if (!mounted) {
      return;
    }

    setState(() {
      isGenerating = false;
      statusText = event.success ? '生成完了' : '生成失敗: ${event.errorDetails ?? "unknown"}';
    });
    scrollToBottom();
  }

  void setStatus(String text) {
    setState(() {
      statusText = text;
    });
  }

  void scrollToBottom() {
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!scrollController.hasClients) {
        return;
      }
      scrollController.animateTo(
        scrollController.position.maxScrollExtent + 120,
        duration: const Duration(milliseconds: 200),
        curve: Curves.easeOut,
      );
    });
  }

  @override
  Widget build(BuildContext context) {
    final Widget setupPanel = Material(
      color: Theme.of(context).colorScheme.surfaceContainerLowest,
      child: ListView(
        padding: const EdgeInsets.all(16),
        children: <Widget>[
          Text(
            'Model Setup',
            style: Theme.of(context).textTheme.titleMedium,
          ),
          const SizedBox(height: 12),
          TextField(
            controller: modelPathController,
            readOnly: selectedDiscoveredModel != null,
            decoration: InputDecoration(
              border: const OutlineInputBorder(),
              labelText: 'Model path',
              hintText: '/storage/emulated/0/.../model.gguf',
              helperText: selectedDiscoveredModel != null
                  ? 'フォルダ選択中は検出モデルのパスを使用'
                  : '手入力も可能',
            ),
          ),
          const SizedBox(height: 12),
          Wrap(
            spacing: 8,
            runSpacing: 8,
            children: <Widget>[
              FilledButton.tonal(
                onPressed: isScanningDirectory ? null : pickModelDirectory,
                child: Text(isScanningDirectory ? 'Scanning...' : 'Pick folder'),
              ),
              OutlinedButton(
                onPressed: selectedDirectoryPath == null || isScanningDirectory
                    ? null
                    : () => scanModelDirectory(selectedDirectoryPath!),
                child: const Text('Rescan folder'),
              ),
              OutlinedButton(
                onPressed: discoveredModels.isEmpty && selectedDirectoryPath == null
                    ? null
                    : () {
                        setState(() {
                          selectedDirectoryPath = null;
                          selectedDiscoveredModel = null;
                          discoveredModels.clear();
                          statusText = 'フォルダ選択を解除しました。';
                        });
                      },
                child: const Text('Clear folder'),
              ),
            ],
          ),
          if (selectedDirectoryPath != null) ...<Widget>[
            const SizedBox(height: 12),
            SelectableText(
              'Selected folder:\n$selectedDirectoryPath',
              style: Theme.of(context).textTheme.bodySmall,
            ),
          ],
          if (discoveredModels.isNotEmpty) ...<Widget>[
            const SizedBox(height: 12),
            DropdownButtonFormField<DiscoveredModel>(
              initialValue: selectedDiscoveredModel,
              decoration: const InputDecoration(
                border: OutlineInputBorder(),
                labelText: 'Detected GGUF file',
              ),
              items: discoveredModels
                  .map(
                    (DiscoveredModel model) => DropdownMenuItem<DiscoveredModel>(
                      value: model,
                      child: Text(
                        model.displayName,
                        overflow: TextOverflow.ellipsis,
                      ),
                    ),
                  )
                  .toList(),
              onChanged: (DiscoveredModel? model) {
                if (model == null) {
                  return;
                }
                setState(() {
                  selectedDiscoveredModel = model;
                  modelPathController.text = model.path;
                });
              },
            ),
          ],
          const SizedBox(height: 12),
          TextField(
            controller: nativeLibraryPathController,
            decoration: const InputDecoration(
              border: OutlineInputBorder(),
              labelText: 'Native library path',
              hintText: 'Android では通常空欄',
            ),
          ),
          const SizedBox(height: 12),
          DropdownButtonFormField<PromptPreset>(
            initialValue: selectedPreset,
            decoration: const InputDecoration(
              border: OutlineInputBorder(),
              labelText: 'Prompt format',
            ),
            items: PromptPreset.values
                .map(
                  (PromptPreset preset) => DropdownMenuItem<PromptPreset>(
                    value: preset,
                    child: Text(preset.label),
                  ),
                )
                .toList(),
            onChanged: (PromptPreset? preset) {
              if (preset == null) {
                return;
              }
              setState(() {
                selectedPreset = preset;
              });
            },
          ),
          const SizedBox(height: 12),
          TextField(
            controller: systemPromptController,
            minLines: 2,
            maxLines: 4,
            decoration: const InputDecoration(
              border: OutlineInputBorder(),
              labelText: 'System prompt',
            ),
          ),
          const SizedBox(height: 16),
          Text(
            'Inference Params',
            style: Theme.of(context).textTheme.titleMedium,
          ),
          const SizedBox(height: 12),
          TextField(
            controller: nCtxController,
            keyboardType: TextInputType.number,
            decoration: const InputDecoration(
              border: OutlineInputBorder(),
              labelText: 'n_ctx',
            ),
          ),
          const SizedBox(height: 12),
          TextField(
            controller: nPredictController,
            keyboardType: TextInputType.number,
            decoration: const InputDecoration(
              border: OutlineInputBorder(),
              labelText: 'n_predict',
            ),
          ),
          const SizedBox(height: 12),
          TextField(
            controller: temperatureController,
            keyboardType: const TextInputType.numberWithOptions(decimal: true),
            decoration: const InputDecoration(
              border: OutlineInputBorder(),
              labelText: 'temperature',
            ),
          ),
          const SizedBox(height: 12),
          TextField(
            controller: topKController,
            keyboardType: TextInputType.number,
            decoration: const InputDecoration(
              border: OutlineInputBorder(),
              labelText: 'top_k',
            ),
          ),
          const SizedBox(height: 12),
          TextField(
            controller: topPController,
            keyboardType: const TextInputType.numberWithOptions(decimal: true),
            decoration: const InputDecoration(
              border: OutlineInputBorder(),
              labelText: 'top_p',
            ),
          ),
          const SizedBox(height: 16),
          Wrap(
            spacing: 8,
            runSpacing: 8,
            children: <Widget>[
              FilledButton(
                onPressed: isModelLoading ? null : loadModel,
                child: const Text('Load model'),
              ),
              OutlinedButton(
                onPressed: isModelLoading ? null : unloadModel,
                child: const Text('Unload'),
              ),
              OutlinedButton(
                onPressed: resetConversation,
                child: const Text('Reset chat'),
              ),
            ],
          ),
          const SizedBox(height: 16),
          Card(
            child: Padding(
              padding: const EdgeInsets.all(12),
              child: Text(statusText),
            ),
          ),
        ],
      ),
    );

    final Widget chatPanel = Column(
      children: <Widget>[
        Expanded(
          child: ListView.builder(
            controller: scrollController,
            padding: const EdgeInsets.all(16),
            itemCount: messages.length,
            itemBuilder: (BuildContext context, int index) {
              final ChatMessageItem message = messages[index];
              final bool isUser = message.role == MessageRole.user;
              return Align(
                alignment: isUser ? Alignment.centerRight : Alignment.centerLeft,
                child: ConstrainedBox(
                  constraints: const BoxConstraints(maxWidth: 720),
                  child: Card(
                    color: isUser
                        ? Theme.of(context).colorScheme.secondaryContainer
                        : Theme.of(context).colorScheme.surfaceContainerHigh,
                    child: Padding(
                      padding: const EdgeInsets.all(12),
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: <Widget>[
                          Text(
                            switch (message.role) {
                              MessageRole.system => 'System',
                              MessageRole.user => 'User',
                              MessageRole.assistant => 'Assistant',
                            },
                            style: Theme.of(context).textTheme.labelMedium,
                          ),
                          const SizedBox(height: 6),
                          SelectableText(
                            message.text.isEmpty ? '...' : message.text,
                          ),
                        ],
                      ),
                    ),
                  ),
                ),
              );
            },
          ),
        ),
        SafeArea(
          top: false,
          child: Padding(
            padding: const EdgeInsets.fromLTRB(16, 8, 16, 16),
            child: Row(
              children: <Widget>[
                Expanded(
                  child: TextField(
                    controller: inputController,
                    minLines: 1,
                    maxLines: 5,
                    decoration: const InputDecoration(
                      border: OutlineInputBorder(),
                      hintText: 'メッセージを入力',
                    ),
                  ),
                ),
                const SizedBox(width: 12),
                FilledButton(
                  onPressed: isModelReady && !isGenerating ? sendPrompt : null,
                  child: const Text('Send'),
                ),
                const SizedBox(width: 8),
                OutlinedButton(
                  onPressed: isGenerating ? stopGeneration : null,
                  child: const Text('Stop'),
                ),
              ],
            ),
          ),
        ),
      ],
    );

    return Scaffold(
      appBar: AppBar(
        title: const Text('Flutter Llama Gallery'),
      ),
      body: LayoutBuilder(
        builder: (BuildContext context, BoxConstraints constraints) {
          final bool stacked = constraints.maxWidth < 960;
          if (stacked) {
            return Column(
              children: <Widget>[
                Expanded(
                  flex: 4,
                  child: setupPanel,
                ),
                const Divider(height: 1),
                Expanded(
                  flex: 6,
                  child: chatPanel,
                ),
              ],
            );
          }

          return Row(
            children: <Widget>[
              SizedBox(
                width: 360,
                child: setupPanel,
              ),
              const VerticalDivider(width: 1),
              Expanded(child: chatPanel),
            ],
          );
        },
      ),
    );
  }
}
