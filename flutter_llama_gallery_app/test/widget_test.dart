import 'package:flutter_test/flutter_test.dart';

import 'package:flutter_llama_gallery_app/main.dart';

void main() {
  testWidgets('Flutter Llama Gallery が描画される', (WidgetTester tester) async {
    await tester.pumpWidget(const FlutterLlamaGalleryApp());

    expect(find.text('Flutter Llama Gallery'), findsOneWidget);
  });
}
