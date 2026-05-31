import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../services/agent_client.dart';
import '../state/audio_provider.dart';
import '../state/connection_provider.dart';

/// Mobile audio control page. Reads `/api/audio/state` + `/api/audio/devices`
/// via Riverpod async providers and dispatches mutations through the shared
/// [audioControllerProvider] notifier.
///
/// The page is a [ConsumerStatefulWidget] because the volume slider needs
/// local "scrubbing" state — we do NOT spam the API on every drag, only on
/// `onChangeEnd`. While the user is dragging, [Slider.value] reads from the
/// local `_dragValue`; once they release we forward the final value to the
/// controller and revert to the provider's value on the next rebuild.
class AudioPage extends ConsumerStatefulWidget {
  const AudioPage({super.key});

  @override
  ConsumerState<AudioPage> createState() => _AudioPageState();
}

class _AudioPageState extends ConsumerState<AudioPage> {
  double? _dragValue;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final stateAsync = ref.watch(audioStateProvider);
    final devicesAsync = ref.watch(audioDevicesProvider);
    final address = ref.watch(agentAddressProvider);
    final controllerState = ref.watch(audioControllerProvider);

    // Surface mutation errors as a SnackBar exactly once per transition.
    ref.listen<AsyncValue<void>>(audioControllerProvider, (prev, next) {
      next.whenOrNull(error: (error, _) {
        final messenger = ScaffoldMessenger.maybeOf(context);
        if (messenger == null) return;
        final message = error is AgentClientException
            ? AgentClient.describeError(error)
            : '操作失败：$error';
        messenger
          ..clearSnackBars()
          ..showSnackBar(SnackBar(content: Text(message)));
      });
    });

    return Scaffold(
      appBar: AppBar(
        title: const Text('音频控制'),
        actions: [
          IconButton(
            tooltip: '刷新',
            onPressed: () {
              ref.invalidate(audioStateProvider);
              ref.invalidate(audioDevicesProvider);
            },
            icon: const Icon(Icons.refresh),
          ),
        ],
      ),
      body: SafeArea(
        child: ListView(
          padding: const EdgeInsets.all(16),
          children: [
            _ConnectionBar(address: address),
            const SizedBox(height: 16),
            _StateSection(
              key: const Key('audio-state-section'),
              stateAsync: stateAsync,
              devicesAsync: devicesAsync,
              dragValue: _dragValue,
              busy: controllerState.isLoading,
              onChanged: (value) => setState(() => _dragValue = value),
              onChangeEnd: (value) async {
                setState(() => _dragValue = null);
                await ref
                    .read(audioControllerProvider.notifier)
                    .setVolume(value);
              },
              onMuteToggle: (muted) async {
                await ref
                    .read(audioControllerProvider.notifier)
                    .setMute(muted);
              },
              onRetry: () => ref.invalidate(audioStateProvider),
              theme: theme,
            ),
            const SizedBox(height: 24),
            Text('输出设备', style: theme.textTheme.titleMedium),
            const SizedBox(height: 8),
            _DeviceSection(
              key: const Key('audio-device-section'),
              devicesAsync: devicesAsync,
              busy: controllerState.isLoading,
              onSelect: (id) async {
                await ref
                    .read(audioControllerProvider.notifier)
                    .switchDevice(id);
              },
              onRetry: () => ref.invalidate(audioDevicesProvider),
            ),
          ],
        ),
      ),
    );
  }
}

class _ConnectionBar extends StatelessWidget {
  const _ConnectionBar({required this.address});

  final String address;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
      decoration: BoxDecoration(
        color: theme.colorScheme.primaryContainer,
        borderRadius: BorderRadius.circular(8),
      ),
      child: Row(
        children: [
          Icon(Icons.link, color: theme.colorScheme.onPrimaryContainer),
          const SizedBox(width: 8),
          Expanded(
            child: Text(
              '已连接：$address',
              style: theme.textTheme.bodyMedium?.copyWith(
                color: theme.colorScheme.onPrimaryContainer,
              ),
            ),
          ),
        ],
      ),
    );
  }
}

class _StateSection extends StatelessWidget {
  const _StateSection({
    super.key,
    required this.stateAsync,
    required this.devicesAsync,
    required this.dragValue,
    required this.busy,
    required this.onChanged,
    required this.onChangeEnd,
    required this.onMuteToggle,
    required this.onRetry,
    required this.theme,
  });

  final AsyncValue<AudioState> stateAsync;
  final AsyncValue<List<AudioDevice>> devicesAsync;
  final double? dragValue;
  final bool busy;
  final ValueChanged<double> onChanged;
  final ValueChanged<double> onChangeEnd;
  final ValueChanged<bool> onMuteToggle;
  final VoidCallback onRetry;
  final ThemeData theme;

  @override
  Widget build(BuildContext context) {
    return stateAsync.when(
      loading: () => const _LoadingCard(label: '正在读取音频状态...'),
      error: (error, _) => _ErrorCard(error: error, onRetry: onRetry),
      data: (state) {
        final defaultDevice = devicesAsync.maybeWhen(
          data: (devices) {
            for (final d in devices) {
              if (d.id == state.defaultDeviceId || d.isDefault) {
                return d;
              }
            }
            return null;
          },
          orElse: () => null,
        );

        final sliderValue = (dragValue ?? state.masterVolume).clamp(0.0, 1.0);
        final percent = (sliderValue * 100).round();

        return Card(
          margin: EdgeInsets.zero,
          child: Padding(
            padding: const EdgeInsets.all(16),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  children: [
                    Icon(
                      state.muted ? Icons.volume_off : Icons.volume_up,
                      color: theme.colorScheme.primary,
                    ),
                    const SizedBox(width: 8),
                    Expanded(
                      child: Text(
                        defaultDevice?.name ?? '未识别的输出设备',
                        style: theme.textTheme.titleLarge,
                        overflow: TextOverflow.ellipsis,
                      ),
                    ),
                    IconButton(
                      key: const Key('audio-mute-toggle'),
                      tooltip: state.muted ? '取消静音' : '静音',
                      onPressed: busy ? null : () => onMuteToggle(!state.muted),
                      icon: Icon(
                        state.muted ? Icons.volume_off : Icons.volume_up,
                      ),
                    ),
                  ],
                ),
                const SizedBox(height: 8),
                Row(
                  children: [
                    SizedBox(
                      width: 48,
                      child: Text(
                        '$percent%',
                        textAlign: TextAlign.end,
                        style: theme.textTheme.titleMedium,
                      ),
                    ),
                    Expanded(
                      child: Slider(
                        value: sliderValue.toDouble(),
                        onChanged: busy ? null : onChanged,
                        onChangeEnd: busy ? null : onChangeEnd,
                      ),
                    ),
                  ],
                ),
              ],
            ),
          ),
        );
      },
    );
  }
}

class _DeviceSection extends StatelessWidget {
  const _DeviceSection({
    super.key,
    required this.devicesAsync,
    required this.busy,
    required this.onSelect,
    required this.onRetry,
  });

  final AsyncValue<List<AudioDevice>> devicesAsync;
  final bool busy;
  final ValueChanged<String> onSelect;
  final VoidCallback onRetry;

  @override
  Widget build(BuildContext context) {
    return devicesAsync.when(
      loading: () => const _LoadingCard(label: '正在读取设备列表...'),
      error: (error, _) => _ErrorCard(error: error, onRetry: onRetry),
      data: (devices) {
        if (devices.isEmpty) {
          return const Card(
            margin: EdgeInsets.zero,
            child: Padding(
              padding: EdgeInsets.all(16),
              child: Text('未发现可用的输出设备。'),
            ),
          );
        }
        return Card(
          margin: EdgeInsets.zero,
          child: ListView.separated(
            shrinkWrap: true,
            physics: const NeverScrollableScrollPhysics(),
            padding: EdgeInsets.zero,
            itemCount: devices.length,
            separatorBuilder: (_, _) => const Divider(height: 1),
            itemBuilder: (context, index) {
              final device = devices[index];
              return ListTile(
                leading: Icon(
                  device.isDefault
                      ? Icons.check_circle
                      : Icons.radio_button_unchecked,
                  color: device.isDefault
                      ? Theme.of(context).colorScheme.primary
                      : null,
                ),
                title: Text(device.name),
                subtitle: Text(_describeState(device.state)),
                trailing: device.isDefault
                    ? const Text('默认')
                    : const Icon(Icons.arrow_forward_ios, size: 16),
                onTap: busy || device.isDefault
                    ? null
                    : () => onSelect(device.id),
              );
            },
          ),
        );
      },
    );
  }

  static String _describeState(String state) {
    switch (state.toLowerCase()) {
      case 'active':
        return '可用';
      case 'disabled':
        return '已禁用';
      case 'unplugged':
        return '未连接';
      case 'notpresent':
        return '未识别';
      default:
        return state.isEmpty ? '未知状态' : state;
    }
  }
}

class _LoadingCard extends StatelessWidget {
  const _LoadingCard({required this.label});

  final String label;

  @override
  Widget build(BuildContext context) {
    return Card(
      margin: EdgeInsets.zero,
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Row(
          children: [
            const SizedBox(
              width: 18,
              height: 18,
              child: CircularProgressIndicator(strokeWidth: 2),
            ),
            const SizedBox(width: 12),
            Expanded(child: Text(label)),
          ],
        ),
      ),
    );
  }
}

class _ErrorCard extends StatelessWidget {
  const _ErrorCard({required this.error, required this.onRetry});

  final Object error;
  final VoidCallback onRetry;

  @override
  Widget build(BuildContext context) {
    final message = error is AgentClientException
        ? AgentClient.describeError(error as AgentClientException)
        : '请求失败：$error';
    final theme = Theme.of(context);
    return Card(
      margin: EdgeInsets.zero,
      color: theme.colorScheme.errorContainer,
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Icon(Icons.error_outline,
                    color: theme.colorScheme.onErrorContainer),
                const SizedBox(width: 8),
                Expanded(
                  child: Text(
                    message,
                    style: theme.textTheme.bodyMedium?.copyWith(
                      color: theme.colorScheme.onErrorContainer,
                    ),
                  ),
                ),
              ],
            ),
            const SizedBox(height: 12),
            Align(
              alignment: Alignment.centerRight,
              child: FilledButton.icon(
                onPressed: onRetry,
                icon: const Icon(Icons.refresh),
                label: const Text('重试'),
              ),
            ),
          ],
        ),
      ),
    );
  }
}
