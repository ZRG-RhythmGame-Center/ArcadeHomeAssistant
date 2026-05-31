import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:maimai_home_mobile/main.dart';
import 'package:shared_preferences/shared_preferences.dart';

void main() {
  testWidgets('app launches directly on ConnectionPage (no auth gate)',
      (tester) async {
    SharedPreferences.setMockInitialValues({});

    await tester.pumpWidget(
      const ProviderScope(child: MaimaiHomeMobileApp()),
    );
    // ConnectionPage subscribes to async providers; pump a few frames so
    // the static UI is laid out before assertions, but avoid pumpAndSettle
    // because the discovery provider may schedule a long-running scan.
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 100));

    // ConnectionPage AppBar title.
    expect(find.text('连接 Windows Agent'), findsOneWidget);
    // No pairing UI exists anymore.
    expect(find.text('配对'), findsNothing);
  });
}
