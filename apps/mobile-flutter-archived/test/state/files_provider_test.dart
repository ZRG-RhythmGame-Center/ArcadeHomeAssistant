import 'dart:io';

import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:maimai_home_mobile/services/agent_client.dart';
import 'package:maimai_home_mobile/state/files_provider.dart';
import 'package:mocktail/mocktail.dart';

class _MockAgentClient extends Mock implements AgentClient {}

class _FakeFile extends Fake implements File {}

void main() {
  setUpAll(() {
    registerFallbackValue(_FakeFile());
  });

  group('fileRootsProvider', () {
    test('returns roots from client.fetchFileRoots()', () async {
      final client = _MockAgentClient();
      when(() => client.fetchFileRoots()).thenAnswer((_) async => const [
            FileRoot(id: 'documents', name: 'Documents', readOnly: false),
            FileRoot(id: 'music', name: 'Music', readOnly: true),
          ]);

      final container = ProviderContainer(overrides: [
        fileApiClientProvider.overrideWithValue(client),
      ]);
      addTearDown(container.dispose);

      final roots = await container.read(fileRootsProvider.future);
      expect(roots, hasLength(2));
      expect(roots.first.id, 'documents');
      expect(roots[1].readOnly, isTrue);
    });

    test('propagates AgentClientException on failure', () async {
      final client = _MockAgentClient();
      when(() => client.fetchFileRoots()).thenThrow(
        const AgentClientException(AgentException.network, 'down'),
      );

      final container = ProviderContainer(overrides: [
        fileApiClientProvider.overrideWithValue(client),
      ]);
      addTearDown(container.dispose);

      await expectLater(
        container.read(fileRootsProvider.future),
        throwsA(isA<AgentClientException>()),
      );
    });
  });

  group('fileListingProvider', () {
    test('fetchListing returns entries', () async {
      final client = _MockAgentClient();
      when(() => client.fetchFiles(any(), any(), limit: any(named: 'limit')))
          .thenAnswer((_) async => FileListingResult(
                entries: [
                  FileEntry(
                    name: 'sub',
                    kind: 'dir',
                    size: null,
                    modified: DateTime.utc(2026, 5, 31, 10),
                  ),
                  FileEntry(
                    name: 'a.txt',
                    kind: 'file',
                    size: 42,
                    modified: DateTime.utc(2026, 5, 31, 11),
                  ),
                ],
                total: 2,
                truncated: false,
              ));

      final container = ProviderContainer(overrides: [
        fileApiClientProvider.overrideWithValue(client),
      ]);
      addTearDown(container.dispose);

      final result =
          await container.read(fileListingProvider(('documents', '')).future);

      expect(result.entries, hasLength(2));
      expect(result.entries.first.name, 'sub');
      expect(result.entries.first.kind, 'dir');
      expect(result.entries[1].size, 42);
      expect(result.total, 2);
      expect(result.truncated, isFalse);
      verify(() => client.fetchFiles('documents', '', limit: any(named: 'limit'))).called(1);
    });

    test('returns truncated=true when listing is large', () async {
      final client = _MockAgentClient();
      when(() => client.fetchFiles(any(), any(), limit: any(named: 'limit')))
          .thenAnswer((_) async => FileListingResult(
                entries: const [],
                total: 600,
                truncated: true,
              ));

      final container = ProviderContainer(overrides: [
        fileApiClientProvider.overrideWithValue(client),
      ]);
      addTearDown(container.dispose);

      final result =
          await container.read(fileListingProvider(('documents', 'sub')).future);
      expect(result.truncated, isTrue);
      expect(result.total, 600);
    });
  });

  group('fileMutationsProvider', () {
    test('delete invalidates listing', () async {
      final client = _MockAgentClient();
      var fetchCount = 0;
      when(() => client.fetchFiles(any(), any(), limit: any(named: 'limit')))
          .thenAnswer((_) async {
        fetchCount++;
        return const FileListingResult(
          entries: [],
          total: 0,
          truncated: false,
        );
      });
      when(() => client.deleteFile(any(), any())).thenAnswer((_) async {});

      final container = ProviderContainer(overrides: [
        fileApiClientProvider.overrideWithValue(client),
      ]);
      addTearDown(container.dispose);

      // Keep listing alive across the mutation to test invalidation.
      final sub = container.listen(
        fileListingProvider(('documents', '')),
        (_, __) {},
      );
      addTearDown(sub.close);

      await container.read(fileListingProvider(('documents', '')).future);
      expect(fetchCount, 1);

      await container
          .read(fileMutationsProvider.notifier)
          .delete(rootId: 'documents', path: 'foo.txt');

      await container.read(fileListingProvider(('documents', '')).future);
      expect(fetchCount, 2);
      verify(() => client.deleteFile('documents', 'foo.txt')).called(1);
    });

    test('rename invalidates listing', () async {
      final client = _MockAgentClient();
      var fetchCount = 0;
      when(() => client.fetchFiles(any(), any(), limit: any(named: 'limit')))
          .thenAnswer((_) async {
        fetchCount++;
        return const FileListingResult(
          entries: [],
          total: 0,
          truncated: false,
        );
      });
      when(() => client.renameFile(any(), any(), any())).thenAnswer((_) async {});

      final container = ProviderContainer(overrides: [
        fileApiClientProvider.overrideWithValue(client),
      ]);
      addTearDown(container.dispose);

      final sub = container.listen(
        fileListingProvider(('documents', 'sub')),
        (_, __) {},
      );
      addTearDown(sub.close);

      await container.read(fileListingProvider(('documents', 'sub')).future);
      expect(fetchCount, 1);

      await container
          .read(fileMutationsProvider.notifier)
          .rename(rootId: 'documents', path: 'sub/old.txt', newName: 'new.txt');

      await container.read(fileListingProvider(('documents', 'sub')).future);
      expect(fetchCount, 2);
      verify(() => client.renameFile('documents', 'sub/old.txt', 'new.txt'))
          .called(1);
    });

    test('move invalidates source and destination listings', () async {
      final client = _MockAgentClient();
      final fetchCounts = <String, int>{};
      when(() => client.fetchFiles(any(), any(), limit: any(named: 'limit')))
          .thenAnswer((invocation) async {
        final path = invocation.positionalArguments[1] as String;
        fetchCounts[path] = (fetchCounts[path] ?? 0) + 1;
        return const FileListingResult(
          entries: [],
          total: 0,
          truncated: false,
        );
      });
      when(() => client.moveFile(any(), any(), any())).thenAnswer((_) async {});

      final container = ProviderContainer(overrides: [
        fileApiClientProvider.overrideWithValue(client),
      ]);
      addTearDown(container.dispose);

      final subA = container.listen(
        fileListingProvider(('documents', 'a')),
        (_, __) {},
      );
      final subB = container.listen(
        fileListingProvider(('documents', 'b')),
        (_, __) {},
      );
      addTearDown(subA.close);
      addTearDown(subB.close);

      await container.read(fileListingProvider(('documents', 'a')).future);
      await container.read(fileListingProvider(('documents', 'b')).future);
      expect(fetchCounts['a'], 1);
      expect(fetchCounts['b'], 1);

      await container.read(fileMutationsProvider.notifier).move(
            rootId: 'documents',
            fromPath: 'a/file.txt',
            toPath: 'b/file.txt',
          );

      await container.read(fileListingProvider(('documents', 'a')).future);
      await container.read(fileListingProvider(('documents', 'b')).future);
      expect(fetchCounts['a'], 2);
      expect(fetchCounts['b'], 2);
      verify(() => client.moveFile('documents', 'a/file.txt', 'b/file.txt'))
          .called(1);
    });

    test('upload invalidates listing on the destination directory', () async {
      final client = _MockAgentClient();
      var fetchCount = 0;
      when(() => client.fetchFiles(any(), any(), limit: any(named: 'limit')))
          .thenAnswer((_) async {
        fetchCount++;
        return const FileListingResult(
          entries: [],
          total: 0,
          truncated: false,
        );
      });
      when(() => client.uploadFile(any(), any(), any(),
          overwrite: any(named: 'overwrite'))).thenAnswer((_) async {});

      final container = ProviderContainer(overrides: [
        fileApiClientProvider.overrideWithValue(client),
      ]);
      addTearDown(container.dispose);

      final sub = container.listen(
        fileListingProvider(('documents', 'incoming')),
        (_, __) {},
      );
      addTearDown(sub.close);

      await container.read(fileListingProvider(('documents', 'incoming')).future);
      expect(fetchCount, 1);

      // Use a real but empty temp file so File.path resolves.
      final tmp = File('${Directory.systemTemp.path}/upload-test.txt');
      await tmp.writeAsString('hi');
      addTearDown(() async {
        if (await tmp.exists()) await tmp.delete();
      });

      await container.read(fileMutationsProvider.notifier).upload(
            rootId: 'documents',
            directory: 'incoming',
            file: tmp,
          );

      await container.read(fileListingProvider(('documents', 'incoming')).future);
      expect(fetchCount, 2);
    });
  });
}
