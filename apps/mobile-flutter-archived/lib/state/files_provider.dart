import 'dart:io';

import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../services/agent_client.dart';
import 'connection_provider.dart';

/// Re-export so callers don't need a second import to use [FileRoot] et al.
export '../services/agent_client.dart'
    show FileRoot, FileEntry, FileListingResult;

/// (rootId, path) family key for [fileListingProvider]. A typedef keeps
/// call-sites readable: `fileListingProvider((rootId, path))`.
typedef FileListingKey = (String rootId, String path);

/// Override seam so widget/state tests can inject a mock [AgentClient]
/// without rebuilding the entire connection flow.
///
/// In production this falls back to constructing a fresh [AgentClient] from
/// the active [agentAddressProvider]; the connection flow keeps its own
/// short-lived client (closed in [ConnectionNotifier.connect]), so file ops
/// own their instance independently.
final fileApiClientProvider = Provider<AgentClient>((ref) {
  final factory = ref.watch(agentClientFactoryProvider);
  final address = ref.watch(agentAddressProvider);
  final client = factory(address);
  ref.onDispose(client.close);
  return client;
});

/// `GET /api/file-roots` — returns the list of configured roots.
final fileRootsProvider = FutureProvider<List<FileRoot>>((ref) async {
  final client = ref.watch(fileApiClientProvider);
  return client.fetchFileRoots();
});

/// `GET /api/files` — single-level listing for a given (rootId, path).
final fileListingProvider =
    FutureProvider.family<FileListingResult, FileListingKey>((ref, key) async {
  final client = ref.watch(fileApiClientProvider);
  return client.fetchFiles(key.$1, key.$2);
});

/// Imperative file-mutation surface (delete / rename / move / upload).
///
/// All methods invalidate the relevant [fileListingProvider] family entries
/// after a successful API call so subsequent reads pick up the new state.
/// Errors propagate as [AgentClientException] — UI is responsible for
/// surfacing them.
class FileMutationsNotifier extends Notifier<void> {
  @override
  void build() {
    // No state — this notifier is purely action-driven.
  }

  AgentClient get _client => ref.read(fileApiClientProvider);

  /// Delete a single file. The wire request always carries `confirm:true`;
  /// the UI confirmation dialog is enforced separately at the widget level.
  Future<void> delete({
    required String rootId,
    required String path,
  }) async {
    await _client.deleteFile(rootId, path);
    _invalidateParentOf(rootId, path);
  }

  /// Rename a file in place. Only the basename changes; the parent directory
  /// is preserved.
  Future<void> rename({
    required String rootId,
    required String path,
    required String newName,
  }) async {
    await _client.renameFile(rootId, path, newName);
    _invalidateParentOf(rootId, path);
  }

  /// Move a file within the same root. Cross-root moves are not supported
  /// by design — pass the same [rootId] for both source and destination.
  Future<void> move({
    required String rootId,
    required String fromPath,
    required String toPath,
  }) async {
    await _client.moveFile(rootId, fromPath, toPath);
    _invalidateParentOf(rootId, fromPath);
    _invalidateParentOf(rootId, toPath);
  }

  /// Upload [file] to `<root>/<directory>/<basename(file.path)>`.
  ///
  /// [directory] is the destination directory relative to the root (use the
  /// empty string for the root itself). [overwrite] maps to the agent's
  /// `overwrite` form field; default `false` preserves existing files.
  Future<void> upload({
    required String rootId,
    required String directory,
    required File file,
    bool overwrite = false,
  }) async {
    final fileName = _basename(file.path);
    final destPath = directory.isEmpty
        ? fileName
        : '${_stripTrailingSlash(directory)}/$fileName';
    await _client.uploadFile(rootId, destPath, file, overwrite: overwrite);
    ref.invalidate(fileListingProvider((rootId, directory)));
  }

  /// Refresh the listing at [path] inside [rootId]. Useful after pull-to-refresh.
  void refresh(String rootId, String path) {
    ref.invalidate(fileListingProvider((rootId, path)));
  }

  void _invalidateParentOf(String rootId, String path) {
    final parent = _parentOf(path);
    ref.invalidate(fileListingProvider((rootId, parent)));
  }

  static String _parentOf(String path) {
    if (path.isEmpty) return '';
    final normalized =
        path.replaceAll('\\', '/').replaceAll(RegExp(r'/+$'), '');
    final idx = normalized.lastIndexOf('/');
    if (idx <= 0) return '';
    return normalized.substring(0, idx);
  }

  static String _basename(String path) {
    final normalized = path.replaceAll('\\', '/');
    final idx = normalized.lastIndexOf('/');
    if (idx < 0) return normalized;
    return normalized.substring(idx + 1);
  }

  static String _stripTrailingSlash(String value) {
    final normalized = value.replaceAll('\\', '/');
    if (normalized.endsWith('/')) {
      return normalized.substring(0, normalized.length - 1);
    }
    return normalized;
  }
}

final fileMutationsProvider =
    NotifierProvider<FileMutationsNotifier, void>(FileMutationsNotifier.new);
