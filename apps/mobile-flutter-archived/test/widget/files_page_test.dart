import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:maimai_home_mobile/pages/files_page.dart';
import 'package:maimai_home_mobile/services/agent_client.dart';
import 'package:maimai_home_mobile/state/files_provider.dart';
import 'package:mocktail/mocktail.dart';

class _MockAgentClient extends Mock implements AgentClient {}

void main() {
  setUpAll(() {
    registerFallbackValue(const FileRoot(id: '_', name: '_', readOnly: false));
  });

  Future<void> pumpPage(WidgetTester tester, AgentClient client) async {
    await tester.pumpWidget(
      ProviderScope(
        overrides: [fileApiClientProvider.overrideWithValue(client)],
        child: const MaterialApp(home: FilesPage()),
      ),
    );
    // resolve fileRootsProvider future
    await tester.pump();
    await tester.pumpAndSettle(const Duration(milliseconds: 200));
  }

  testWidgets('renders root list when fetchFileRoots succeeds',
      (tester) async {
    final client = _MockAgentClient();
    when(() => client.fetchFileRoots()).thenAnswer((_) async => const [
          FileRoot(id: 'documents', name: '我的文档', readOnly: false),
          FileRoot(id: 'music', name: '音乐', readOnly: true),
        ]);
    when(() => client.fetchFiles(any(), any(), limit: any(named: 'limit')))
        .thenAnswer((_) async => const FileListingResult(
              entries: [],
              total: 0,
              truncated: false,
            ));

    await pumpPage(tester, client);

    expect(find.text('我的文档'), findsOneWidget);
    expect(find.text('音乐'), findsOneWidget);
  });

  testWidgets('breadcrumb navigates back to parent directory',
      (tester) async {
    final client = _MockAgentClient();
    when(() => client.fetchFileRoots()).thenAnswer((_) async => const [
          FileRoot(id: 'documents', name: 'Documents', readOnly: false),
        ]);
    // Root listing contains a folder.
    when(() => client.fetchFiles('documents', '', limit: any(named: 'limit')))
        .thenAnswer((_) async => FileListingResult(
              entries: [
                FileEntry(
                  name: 'sub',
                  kind: 'dir',
                  size: null,
                  modified: DateTime.utc(2026, 1, 1),
                ),
              ],
              total: 1,
              truncated: false,
            ));
    when(() => client.fetchFiles('documents', 'sub', limit: any(named: 'limit')))
        .thenAnswer((_) async => FileListingResult(
              entries: [
                FileEntry(
                  name: 'inner.txt',
                  kind: 'file',
                  size: 7,
                  modified: DateTime.utc(2026, 1, 1),
                ),
              ],
              total: 1,
              truncated: false,
            ));

    await pumpPage(tester, client);

    // Tap the root tile to enter it.
    await tester.tap(find.text('Documents'));
    await tester.pumpAndSettle();

    // We should now see the directory listing for the root.
    expect(find.text('sub'), findsOneWidget);

    // Tap the folder to descend.
    await tester.tap(find.text('sub'));
    await tester.pumpAndSettle();

    expect(find.text('inner.txt'), findsOneWidget);

    // Breadcrumb should expose at least one ancestor we can tap to go back.
    final rootCrumb = find.descendant(
      of: find.byKey(const Key('files-breadcrumb')),
      matching: find.text('Documents'),
    );
    expect(rootCrumb, findsOneWidget);

    await tester.tap(rootCrumb);
    await tester.pumpAndSettle();

    // Back at root listing - we should see 'sub' again, not 'inner.txt'.
    expect(find.text('sub'), findsOneWidget);
    expect(find.text('inner.txt'), findsNothing);
  });

  testWidgets('delete shows confirmation dialog and calls deleteFile on confirm',
      (tester) async {
    final client = _MockAgentClient();
    when(() => client.fetchFileRoots()).thenAnswer((_) async => const [
          FileRoot(id: 'documents', name: 'Documents', readOnly: false),
        ]);
    when(() => client.fetchFiles('documents', '', limit: any(named: 'limit')))
        .thenAnswer((_) async => FileListingResult(
              entries: [
                FileEntry(
                  name: 'doomed.txt',
                  kind: 'file',
                  size: 5,
                  modified: DateTime.utc(2026, 1, 1),
                ),
              ],
              total: 1,
              truncated: false,
            ));
    when(() => client.deleteFile(any(), any())).thenAnswer((_) async {});

    await pumpPage(tester, client);

    await tester.tap(find.text('Documents'));
    await tester.pumpAndSettle();

    // Long-press to open the actions sheet.
    await tester.longPress(find.text('doomed.txt'));
    await tester.pumpAndSettle();

    // Tap "删除" in the bottom sheet.
    await tester.tap(find.text('删除'));
    await tester.pumpAndSettle();

    // Confirmation dialog appears with both Cancel + Delete buttons.
    expect(find.byType(AlertDialog), findsOneWidget);
    expect(find.text('确认删除'), findsOneWidget);

    // Confirm.
    await tester.tap(find.descendant(
      of: find.byType(AlertDialog),
      matching: find.text('删除'),
    ));
    await tester.pumpAndSettle();

    verify(() => client.deleteFile('documents', 'doomed.txt')).called(1);
  });

  testWidgets('truncated banner appears when listing.truncated is true',
      (tester) async {
    final client = _MockAgentClient();
    when(() => client.fetchFileRoots()).thenAnswer((_) async => const [
          FileRoot(id: 'documents', name: 'Documents', readOnly: false),
        ]);
    when(() => client.fetchFiles('documents', '', limit: any(named: 'limit')))
        .thenAnswer((_) async => const FileListingResult(
              entries: [],
              total: 600,
              truncated: true,
            ));

    await pumpPage(tester, client);

    await tester.tap(find.text('Documents'));
    await tester.pumpAndSettle();

    expect(find.byKey(const Key('files-truncated-banner')), findsOneWidget);
  });
}
