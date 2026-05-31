import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../services/agent_client.dart';
import '../state/connection_provider.dart';
import '../state/discovery_provider.dart';
import '../state/storage_provider.dart';
import 'files_page.dart';
import 'audio_page.dart';

/// Top-level connection page. Migrated from the original single-file
/// `main.dart` to a Riverpod-aware ConsumerStatefulWidget so it can:
///   - own the [TextEditingController] lifecycle (local UI state)
///   - subscribe to [connectionStateProvider], [discoveryProvider], and the
///     async [savedAddressProvider]
class ConnectionPage extends ConsumerStatefulWidget {
  const ConnectionPage({super.key});

  @override
  ConsumerState<ConnectionPage> createState() => _ConnectionPageState();
}

class _ConnectionPageState extends ConsumerState<ConnectionPage> {
  final _addressController =
      TextEditingController(text: defaultAgentAddress);
  bool _restoredSavedAddress = false;

  @override
  void dispose() {
    _addressController.dispose();
    super.dispose();
  }

  Future<void> _saveAddress(String address) async {
    final controller =
        await ref.read(savedAddressControllerProvider.future);
    await controller.write(address);
  }

  Future<void> _testConnection() async {
    final raw = _addressController.text.trim();
    ref.read(agentAddressProvider.notifier).state = raw;

    await ref.read(connectionStateProvider.notifier).connect(raw);

    final state = ref.read(connectionStateProvider);
    if (state is Connected) {
      // 仅在成功后保存当前输入地址（与原行为一致）
      await _saveAddress(raw);
      if (!mounted) return;
      await Navigator.of(context).push(
        MaterialPageRoute<void>(builder: (_) => const AudioPage()),
      );
    }
  }

  Future<void> _scan() => ref.read(discoveryProvider.notifier).start();

  Future<void> _useDiscoveredAgent(DiscoveredAgent agent) async {
    _addressController.text = agent.connectAddress;
    ref.read(agentAddressProvider.notifier).state = agent.connectAddress;
    await _saveAddress(agent.connectAddress);
    // 切换到 idle 状态，清掉之前可能残留的错误/成功卡片
    ref.read(connectionStateProvider.notifier).reset();
  }

  void _maybeRestoreSavedAddress(AsyncValue<String?> async) {
    if (_restoredSavedAddress) {
      return;
    }
    async.whenData((value) {
      if (value != null && value.isNotEmpty) {
        _addressController.text = value;
        ref.read(agentAddressProvider.notifier).state = value;
      }
      _restoredSavedAddress = true;
    });
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final connection = ref.watch(connectionStateProvider);
    final discovery = ref.watch(discoveryProvider);
    final savedAsync = ref.watch(savedAddressProvider);

    _maybeRestoreSavedAddress(savedAsync);

    final isLoading = connection is Connecting;
    final isDiscovering = discovery.isDiscovering;
    final discoveryError = discovery.errorMessage;
    String? errorMessage;
    AgentStatus? status;
    if (connection is ConnectionError) {
      errorMessage = connection.message;
    } else if (connection is Connected) {
      status = connection.status;
    }
    errorMessage ??= discoveryError;

    return Scaffold(
      appBar: AppBar(
        title: const Text('连接 Windows Agent'),
      ),
      body: SafeArea(
        child: ListView(
          padding: const EdgeInsets.all(16),
          children: [
            Text(
              '输入 Windows 电脑上的 Agent 地址，或扫描局域网内已广播的 Agent，然后测试与 /api/status 的连通性。',
              style: theme.textTheme.bodyLarge,
            ),
            const SizedBox(height: 16),
            TextField(
              controller: _addressController,
              keyboardType: TextInputType.url,
              decoration: const InputDecoration(
                labelText: 'Agent 地址',
                hintText: '192.168.1.100:8765',
                border: OutlineInputBorder(),
              ),
            ),
            const SizedBox(height: 12),
            Row(
              children: [
                Expanded(
                  child: FilledButton.icon(
                    onPressed: isLoading ? null : _testConnection,
                    icon: isLoading
                        ? const SizedBox(
                            width: 18,
                            height: 18,
                            child: CircularProgressIndicator(strokeWidth: 2),
                          )
                        : const Icon(Icons.wifi_tethering),
                    label: Text(isLoading ? '连接中...' : '测试连接'),
                  ),
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: OutlinedButton.icon(
                    onPressed: isDiscovering ? null : _scan,
                    icon: isDiscovering
                        ? const SizedBox(
                            width: 18,
                            height: 18,
                            child: CircularProgressIndicator(strokeWidth: 2),
                          )
                        : const Icon(Icons.radar),
                    label: Text(isDiscovering ? '扫描中...' : '扫描局域网'),
                  ),
                ),
              ],
            ),
            const SizedBox(height: 20),
            _DiscoverySection(
              agents: discovery.agents,
              isDiscovering: isDiscovering,
              onUseAgent: _useDiscoveredAgent,
            ),
            if (errorMessage != null)
              _MessageCard(
                title: '连接失败',
                color: theme.colorScheme.errorContainer,
                child: Text(errorMessage),
              ),
            if (status != null) _StatusCard(status: status),
          ],
        ),
      ),
    );
  }
}

class _DiscoverySection extends StatelessWidget {
  const _DiscoverySection({
    required this.agents,
    required this.isDiscovering,
    required this.onUseAgent,
  });

  final List<DiscoveredAgent> agents;
  final bool isDiscovering;
  final ValueChanged<DiscoveredAgent> onUseAgent;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);

    return _MessageCard(
      title: '局域网发现',
      color: theme.colorScheme.surfaceContainerHighest,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            '扫描 `_maimai-home._tcp` 广播服务。建议使用真机测试，模拟器不作为发现功能验收环境。',
            style: theme.textTheme.bodyMedium,
          ),
          const SizedBox(height: 12),
          if (isDiscovering) const Text('正在搜索局域网中的 Agent...'),
          if (!isDiscovering && agents.isEmpty)
            const Text('暂无发现结果，点击"扫描局域网"开始搜索。'),
          if (agents.isNotEmpty)
            ...agents.map(
              (agent) => Card(
                margin: const EdgeInsets.only(top: 12),
                child: ListTile(
                  title: Text(agent.name),
                  subtitle:
                      Text('${agent.connectAddress}\n版本：${agent.version}'),
                  isThreeLine: true,
                  trailing: const Icon(Icons.arrow_forward_ios, size: 18),
                  onTap: () => onUseAgent(agent),
                ),
              ),
            ),
        ],
      ),
    );
  }
}

class _StatusCard extends StatelessWidget {
  const _StatusCard({required this.status});

  final AgentStatus status;

  @override
  Widget build(BuildContext context) {
    return _MessageCard(
      title: '连接成功',
      color: Theme.of(context).colorScheme.primaryContainer,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          _StatusRow(label: '地址', value: status.baseUrl),
          _StatusRow(label: '机器名', value: status.machineName),
          _StatusRow(label: '版本', value: status.version),
          _StatusRow(label: '运行秒数', value: status.uptimeSeconds.toString()),
          const SizedBox(height: 12),
          Text('当前能力', style: Theme.of(context).textTheme.titleMedium),
          const SizedBox(height: 8),
          Wrap(
            spacing: 8,
            runSpacing: 8,
            children: [
              _CapabilityChip(label: '音量', enabled: status.audioVolume),
              _CapabilityChip(label: '静音', enabled: status.audioMute),
              _CapabilityChip(label: '设备切换', enabled: status.audioDeviceSwitch),
              _CapabilityChip(label: '文件管理', enabled: status.fileManagement),
              _CapabilityChip(label: '网络发现', enabled: status.discoveryBroadcast),
            ],
          ),
          const SizedBox(height: 16),
          FilledButton.icon(
            icon: const Icon(Icons.folder_open),
            label: const Text('进入文件管理'),
            onPressed: () {
              Navigator.of(context).push(
                MaterialPageRoute<void>(
                  builder: (_) => const FilesPage(),
                ),
              );
            },
          ),
        ],
      ),
    );
  }
}

class _MessageCard extends StatelessWidget {
  const _MessageCard({
    required this.title,
    required this.color,
    required this.child,
  });

  final String title;
  final Color color;
  final Widget child;

  @override
  Widget build(BuildContext context) {
    return Card(
      color: color,
      margin: const EdgeInsets.only(bottom: 16),
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(title, style: Theme.of(context).textTheme.titleLarge),
            const SizedBox(height: 12),
            child,
          ],
        ),
      ),
    );
  }
}

class _StatusRow extends StatelessWidget {
  const _StatusRow({required this.label, required this.value});

  final String label;
  final String value;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 8),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          SizedBox(
            width: 88,
            child:
                Text('$label：', style: Theme.of(context).textTheme.titleSmall),
          ),
          Expanded(child: Text(value)),
        ],
      ),
    );
  }
}

class _CapabilityChip extends StatelessWidget {
  const _CapabilityChip({required this.label, required this.enabled});

  final String label;
  final bool enabled;

  @override
  Widget build(BuildContext context) {
    return Chip(
      avatar: Icon(
        enabled ? Icons.check_circle : Icons.radio_button_unchecked,
        size: 18,
      ),
      label: Text(label),
    );
  }
}
