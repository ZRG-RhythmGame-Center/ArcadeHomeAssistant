import 'dart:io';

import 'package:file_picker/file_picker.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:path_provider/path_provider.dart';

import '../services/agent_client.dart';
import '../state/files_provider.dart';

/// Mobile file browser page.
///
/// Layout:
///   - Empty state: list of available [FileRoot]s; tap one to drill in.
///   - Drilled-in state: AppBar with back button + breadcrumb chips + entry list.
///
/// Mutating actions live on a long-press bottom sheet per entry. Delete shows
/// a confirmation dialog before the API call. The agent client always sends
/// `confirm:true` automatically — UI never sets it.
class FilesPage extends ConsumerStatefulWidget {
  const FilesPage({super.key});

  @override
  ConsumerState<FilesPage> createState() => _FilesPageState();
}

class _FilesPageState extends ConsumerState<FilesPage> {
  /// `null` = root selector view, otherwise we're inside a root.
  FileRoot? _activeRoot;

  /// Path relative to the active root. Empty string means root level.
  /// Internal representation uses forward slashes only.
  String _path = '';

  void _enterRoot(FileRoot root) {
    setState(() {
      _activeRoot = root;
      _path = '';
    });
  }

  void _leaveRoot() {
    setState(() {
      _activeRoot = null;
      _path = '';
    });
  }

  void _enterDirectory(String name) {
    setState(() {
      _path = _path.isEmpty ? name : '$_path/$name';
    });
  }

  void _navigateToCrumb(int index) {
    final segments = _pathSegments;
    setState(() {
      _path = segments.take(index + 1).join('/');
    });
  }

  void _navigateToActiveRoot() {
    setState(() {
      _path = '';
    });
  }

  List<String> get _pathSegments =>
      _path.isEmpty ? const [] : _path.split('/');

  @override
  Widget build(BuildContext context) {
    final root = _activeRoot;
    if (root == null) {
      return _RootSelector(onSelect: _enterRoot);
    }

    return _DirectoryView(
      root: root,
      path: _path,
      segments: _pathSegments,
      onLeaveRoot: _leaveRoot,
      onTapCrumb: _navigateToCrumb,
      onTapRootCrumb: _navigateToActiveRoot,
      onEnterDirectory: _enterDirectory,
    );
  }
}

class _RootSelector extends ConsumerWidget {
  const _RootSelector({required this.onSelect});

  final ValueChanged<FileRoot> onSelect;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final asyncRoots = ref.watch(fileRootsProvider);

    return Scaffold(
      appBar: AppBar(title: const Text('文件管理')),
      body: asyncRoots.when(
        data: (roots) {
          if (roots.isEmpty) {
            return const Center(
              child: Padding(
                padding: EdgeInsets.all(24),
                child: Text('Agent 尚未配置任何文件根目录。'),
              ),
            );
          }
          return RefreshIndicator(
            onRefresh: () async {
              ref.invalidate(fileRootsProvider);
              await ref.read(fileRootsProvider.future);
            },
            child: ListView.separated(
              physics: const AlwaysScrollableScrollPhysics(),
              itemCount: roots.length,
              separatorBuilder: (_, __) => const Divider(height: 0),
              itemBuilder: (context, index) {
                final root = roots[index];
                return ListTile(
                  leading: Icon(
                    root.readOnly ? Icons.folder_shared : Icons.folder,
                    color: Theme.of(context).colorScheme.primary,
                  ),
                  title: Text(root.name),
                  subtitle: Text(
                    root.readOnly ? '只读 · id=${root.id}' : 'id=${root.id}',
                  ),
                  trailing: const Icon(Icons.chevron_right),
                  onTap: () => onSelect(root),
                );
              },
            ),
          );
        },
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (error, _) => _ErrorState(
          message: '获取根目录失败：$error',
          onRetry: () => ref.invalidate(fileRootsProvider),
        ),
      ),
    );
  }
}

class _DirectoryView extends ConsumerWidget {
  const _DirectoryView({
    required this.root,
    required this.path,
    required this.segments,
    required this.onLeaveRoot,
    required this.onTapCrumb,
    required this.onTapRootCrumb,
    required this.onEnterDirectory,
  });

  final FileRoot root;
  final String path;
  final List<String> segments;
  final VoidCallback onLeaveRoot;
  final ValueChanged<int> onTapCrumb;
  final VoidCallback onTapRootCrumb;
  final ValueChanged<String> onEnterDirectory;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final asyncListing = ref.watch(fileListingProvider((root.id, path)));

    return Scaffold(
      appBar: AppBar(
        leading: IconButton(
          icon: const Icon(Icons.arrow_back),
          onPressed: onLeaveRoot,
        ),
        title: Text(root.name),
      ),
      floatingActionButton: root.readOnly
          ? null
          : FloatingActionButton.extended(
              onPressed: () => _pickAndUpload(context, ref),
              icon: const Icon(Icons.upload_file),
              label: const Text('上传'),
            ),
      body: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          _Breadcrumb(
            root: root,
            segments: segments,
            onTapRoot: onTapRootCrumb,
            onTapSegment: onTapCrumb,
          ),
          Expanded(
            child: asyncListing.when(
              data: (listing) {
                return RefreshIndicator(
                  onRefresh: () async {
                    ref.invalidate(fileListingProvider((root.id, path)));
                    await ref
                        .read(fileListingProvider((root.id, path)).future);
                  },
                  child: CustomScrollView(
                    physics: const AlwaysScrollableScrollPhysics(),
                    slivers: [
                      if (listing.truncated)
                        SliverToBoxAdapter(
                          child: _TruncatedBanner(total: listing.total),
                        ),
                      if (listing.entries.isEmpty)
                        const SliverFillRemaining(
                          hasScrollBody: false,
                          child: Center(child: Text('此目录为空')),
                        )
                      else
                        SliverList.separated(
                          itemCount: listing.entries.length,
                          separatorBuilder: (_, __) =>
                              const Divider(height: 0),
                          itemBuilder: (context, index) {
                            final entry = listing.entries[index];
                            return _EntryTile(
                              entry: entry,
                              readOnly: root.readOnly,
                              onTap: () => _onEntryTap(context, ref, entry),
                              onLongPress: () =>
                                  _showActions(context, ref, entry),
                            );
                          },
                        ),
                    ],
                  ),
                );
              },
              loading: () =>
                  const Center(child: CircularProgressIndicator()),
              error: (error, _) => _ErrorState(
                message: '加载目录失败：$error',
                onRetry: () =>
                    ref.invalidate(fileListingProvider((root.id, path))),
              ),
            ),
          ),
        ],
      ),
    );
  }

  void _onEntryTap(BuildContext context, WidgetRef ref, FileEntry entry) {
    if (entry.isDirectory) {
      onEnterDirectory(entry.name);
      return;
    }
    _downloadEntry(context, ref, entry);
  }

  Future<void> _showActions(
    BuildContext context,
    WidgetRef ref,
    FileEntry entry,
  ) async {
    final messenger = ScaffoldMessenger.of(context);
    final result = await showModalBottomSheet<_EntryAction>(
      context: context,
      builder: (sheetContext) {
        return SafeArea(
          child: Wrap(
            children: [
              if (!entry.isDirectory)
                ListTile(
                  leading: const Icon(Icons.download),
                  title: const Text('下载'),
                  onTap: () =>
                      Navigator.of(sheetContext).pop(_EntryAction.download),
                ),
              if (!root.readOnly)
                ListTile(
                  leading: const Icon(Icons.drive_file_rename_outline),
                  title: const Text('重命名'),
                  onTap: () =>
                      Navigator.of(sheetContext).pop(_EntryAction.rename),
                ),
              if (!root.readOnly)
                ListTile(
                  leading: const Icon(Icons.drive_file_move_outline),
                  title: const Text('移动'),
                  onTap: () =>
                      Navigator.of(sheetContext).pop(_EntryAction.move),
                ),
              if (!root.readOnly && !entry.isDirectory)
                ListTile(
                  leading: const Icon(Icons.delete_outline,
                      color: Colors.redAccent),
                  title: const Text('删除',
                      style: TextStyle(color: Colors.redAccent)),
                  onTap: () =>
                      Navigator.of(sheetContext).pop(_EntryAction.delete),
                ),
            ],
          ),
        );
      },
    );

    if (result == null || !context.mounted) return;

    switch (result) {
      case _EntryAction.download:
        await _downloadEntry(context, ref, entry);
        break;
      case _EntryAction.rename:
        await _renameEntry(context, ref, entry, messenger);
        break;
      case _EntryAction.move:
        await _moveEntry(context, ref, entry, messenger);
        break;
      case _EntryAction.delete:
        await _deleteEntry(context, ref, entry, messenger);
        break;
    }
  }

  Future<void> _deleteEntry(
    BuildContext context,
    WidgetRef ref,
    FileEntry entry,
    ScaffoldMessengerState messenger,
  ) async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (dialogContext) {
        return AlertDialog(
          title: const Text('确认删除'),
          content: Text('确定要删除 “${entry.name}” 吗？此操作不可撤销。'),
          actions: [
            TextButton(
              onPressed: () => Navigator.of(dialogContext).pop(false),
              child: const Text('取消'),
            ),
            FilledButton.tonal(
              style: FilledButton.styleFrom(
                foregroundColor: Colors.redAccent,
              ),
              onPressed: () => Navigator.of(dialogContext).pop(true),
              child: const Text('删除'),
            ),
          ],
        );
      },
    );
    if (confirmed != true) return;

    final entryPath = _entryPath(entry);
    try {
      await ref.read(fileMutationsProvider.notifier).delete(
            rootId: root.id,
            path: entryPath,
          );
      messenger.showSnackBar(SnackBar(content: Text('已删除 ${entry.name}')));
    } on AgentClientException catch (error) {
      messenger.showSnackBar(
        SnackBar(content: Text('删除失败：${AgentClient.describeError(error)}')),
      );
    }
  }

  Future<void> _renameEntry(
    BuildContext context,
    WidgetRef ref,
    FileEntry entry,
    ScaffoldMessengerState messenger,
  ) async {
    final controller = TextEditingController(text: entry.name);
    final newName = await showDialog<String>(
      context: context,
      builder: (dialogContext) {
        return AlertDialog(
          title: const Text('重命名'),
          content: TextField(
            controller: controller,
            autofocus: true,
            decoration: const InputDecoration(labelText: '新名称'),
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.of(dialogContext).pop(),
              child: const Text('取消'),
            ),
            FilledButton(
              onPressed: () {
                Navigator.of(dialogContext).pop(controller.text.trim());
              },
              child: const Text('确定'),
            ),
          ],
        );
      },
    );

    if (newName == null || newName.isEmpty || newName == entry.name) return;

    final entryPath = _entryPath(entry);
    try {
      await ref.read(fileMutationsProvider.notifier).rename(
            rootId: root.id,
            path: entryPath,
            newName: newName,
          );
      messenger.showSnackBar(SnackBar(content: Text('已重命名为 $newName')));
    } on AgentClientException catch (error) {
      messenger.showSnackBar(
        SnackBar(content: Text('重命名失败：${AgentClient.describeError(error)}')),
      );
    }
  }

  Future<void> _moveEntry(
    BuildContext context,
    WidgetRef ref,
    FileEntry entry,
    ScaffoldMessengerState messenger,
  ) async {
    final controller = TextEditingController(text: _entryPath(entry));
    final newPath = await showDialog<String>(
      context: context,
      builder: (dialogContext) {
        return AlertDialog(
          title: const Text('移动到'),
          content: TextField(
            controller: controller,
            autofocus: true,
            decoration: const InputDecoration(
              labelText: '目标相对路径',
              helperText: '相对当前根目录，例如 archive/old.txt',
            ),
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.of(dialogContext).pop(),
              child: const Text('取消'),
            ),
            FilledButton(
              onPressed: () {
                Navigator.of(dialogContext).pop(controller.text.trim());
              },
              child: const Text('移动'),
            ),
          ],
        );
      },
    );

    if (newPath == null || newPath.isEmpty) return;

    final entryPath = _entryPath(entry);
    if (newPath == entryPath) return;

    try {
      await ref.read(fileMutationsProvider.notifier).move(
            rootId: root.id,
            fromPath: entryPath,
            toPath: newPath,
          );
      messenger.showSnackBar(SnackBar(content: Text('已移动到 $newPath')));
    } on AgentClientException catch (error) {
      messenger.showSnackBar(
        SnackBar(content: Text('移动失败：${AgentClient.describeError(error)}')),
      );
    }
  }

  Future<void> _downloadEntry(
    BuildContext context,
    WidgetRef ref,
    FileEntry entry,
  ) async {
    if (entry.isDirectory) return;
    final messenger = ScaffoldMessenger.of(context);
    try {
      final dir = await getApplicationDocumentsDirectory();
      final downloads = Directory('${dir.path}${Platform.pathSeparator}downloads');
      if (!downloads.existsSync()) {
        downloads.createSync(recursive: true);
      }
      final savePath =
          '${downloads.path}${Platform.pathSeparator}${entry.name}';
      final entryPath = _entryPath(entry);
      final client = ref.read(fileApiClientProvider);
      await client.downloadFile(root.id, entryPath, savePath);
      messenger.showSnackBar(SnackBar(content: Text('已下载到 $savePath')));
    } on AgentClientException catch (error) {
      messenger.showSnackBar(
        SnackBar(content: Text('下载失败：${AgentClient.describeError(error)}')),
      );
    } catch (error) {
      messenger.showSnackBar(SnackBar(content: Text('下载失败：$error')));
    }
  }

  Future<void> _pickAndUpload(BuildContext context, WidgetRef ref) async {
    final messenger = ScaffoldMessenger.of(context);
    final picked = await FilePicker.platform.pickFiles();
    final pickedPath = picked?.files.single.path;
    if (pickedPath == null) return;

    final file = File(pickedPath);
    try {
      await ref.read(fileMutationsProvider.notifier).upload(
            rootId: root.id,
            directory: path,
            file: file,
          );
      messenger
          .showSnackBar(SnackBar(content: Text('已上传 ${file.uri.pathSegments.last}')));
    } on AgentClientException catch (error) {
      messenger.showSnackBar(
        SnackBar(content: Text('上传失败：${AgentClient.describeError(error)}')),
      );
    }
  }

  String _entryPath(FileEntry entry) =>
      path.isEmpty ? entry.name : '$path/${entry.name}';
}

class _Breadcrumb extends StatelessWidget {
  const _Breadcrumb({
    required this.root,
    required this.segments,
    required this.onTapRoot,
    required this.onTapSegment,
  });

  final FileRoot root;
  final List<String> segments;
  final VoidCallback onTapRoot;
  final ValueChanged<int> onTapSegment;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Container(
      key: const Key('files-breadcrumb'),
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
      color: theme.colorScheme.surfaceContainerHighest,
      child: SingleChildScrollView(
        scrollDirection: Axis.horizontal,
        child: Row(
          children: [
            ActionChip(
              avatar: const Icon(Icons.home, size: 18),
              label: Text(root.name),
              onPressed: onTapRoot,
            ),
            for (var i = 0; i < segments.length; i++) ...[
              const Padding(
                padding: EdgeInsets.symmetric(horizontal: 4),
                child: Icon(Icons.chevron_right, size: 18),
              ),
              ActionChip(
                label: Text(segments[i]),
                onPressed: () => onTapSegment(i),
              ),
            ],
          ],
        ),
      ),
    );
  }
}

class _EntryTile extends StatelessWidget {
  const _EntryTile({
    required this.entry,
    required this.readOnly,
    required this.onTap,
    required this.onLongPress,
  });

  final FileEntry entry;
  final bool readOnly;
  final VoidCallback onTap;
  final VoidCallback onLongPress;

  @override
  Widget build(BuildContext context) {
    return ListTile(
      leading: Icon(
        entry.isDirectory ? Icons.folder : Icons.insert_drive_file,
        color: entry.isDirectory
            ? Theme.of(context).colorScheme.primary
            : Theme.of(context).colorScheme.secondary,
      ),
      title: Text(entry.name),
      subtitle: Text(_subtitle(entry)),
      trailing: entry.isDirectory ? const Icon(Icons.chevron_right) : null,
      onTap: onTap,
      onLongPress: onLongPress,
    );
  }

  String _subtitle(FileEntry entry) {
    final modified = entry.modified.toLocal();
    final stamp =
        '${modified.year}-${_two(modified.month)}-${_two(modified.day)} '
        '${_two(modified.hour)}:${_two(modified.minute)}';
    if (entry.isDirectory) return stamp;
    return '${_humanSize(entry.size ?? 0)} · $stamp';
  }

  static String _two(int n) => n.toString().padLeft(2, '0');

  static String _humanSize(int bytes) {
    if (bytes < 1024) return '$bytes B';
    if (bytes < 1024 * 1024) return '${(bytes / 1024).toStringAsFixed(1)} KB';
    if (bytes < 1024 * 1024 * 1024) {
      return '${(bytes / (1024 * 1024)).toStringAsFixed(1)} MB';
    }
    return '${(bytes / (1024 * 1024 * 1024)).toStringAsFixed(2)} GB';
  }
}

class _TruncatedBanner extends StatelessWidget {
  const _TruncatedBanner({required this.total});

  final int total;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Container(
      key: const Key('files-truncated-banner'),
      width: double.infinity,
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
      color: theme.colorScheme.tertiaryContainer,
      child: Row(
        children: [
          const Icon(Icons.info_outline),
          const SizedBox(width: 12),
          Expanded(
            child: Text(
              '此目录条目过多（共 $total 项），仅显示前 500 项。建议进入子目录查看更多内容。',
              style: theme.textTheme.bodyMedium,
            ),
          ),
        ],
      ),
    );
  }
}

class _ErrorState extends StatelessWidget {
  const _ErrorState({required this.message, required this.onRetry});

  final String message;
  final VoidCallback onRetry;

  @override
  Widget build(BuildContext context) {
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(24),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const Icon(Icons.error_outline, size: 48),
            const SizedBox(height: 12),
            Text(message, textAlign: TextAlign.center),
            const SizedBox(height: 12),
            FilledButton.icon(
              onPressed: onRetry,
              icon: const Icon(Icons.refresh),
              label: const Text('重试'),
            ),
          ],
        ),
      ),
    );
  }
}

enum _EntryAction { download, rename, move, delete }
