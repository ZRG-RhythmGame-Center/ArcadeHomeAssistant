import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../services/agent_client.dart';
import 'connection_provider.dart';

/// Builds an [AgentClient] bound to the current [agentAddressProvider]. Tests
/// override this directly via `agentClientProvider.overrideWithValue(mock)` so
/// they don't need to set the address provider first.
final agentClientProvider = Provider<AgentClient>((ref) {
  final address = ref.watch(agentAddressProvider);
  final client = AgentClient(baseUrl: address);
  ref.onDispose(client.close);
  return client;
});

/// Current master volume / mute / default device id. Refetched by invalidating
/// this provider after any successful mutation.
final audioStateProvider = FutureProvider<AudioState>((ref) async {
  final client = ref.watch(agentClientProvider);
  return client.fetchAudioState();
});

/// Render endpoint list for the device picker. Invalidated after a successful
/// `switchDevice` or whenever the realtime layer reports a device change.
final audioDevicesProvider = FutureProvider<List<AudioDevice>>((ref) async {
  final client = ref.watch(agentClientProvider);
  return client.fetchAudioDevices();
});

/// Imperative controller for audio mutations. The [AsyncValue<void>] state lets
/// the UI react to in-flight / failed calls without coupling to the async
/// providers above; on success the relevant data provider is invalidated so
/// any [Consumer] watching it sees the fresh value on the next frame.
class AudioController extends AsyncNotifier<void> {
  @override
  Future<void> build() async {}

  Future<void> setVolume(double level) async {
    await _run(() async {
      final client = ref.read(agentClientProvider);
      await client.setVolume(level);
      ref.invalidate(audioStateProvider);
    });
  }

  Future<void> setMute(bool muted) async {
    await _run(() async {
      final client = ref.read(agentClientProvider);
      await client.setMute(muted);
      ref.invalidate(audioStateProvider);
    });
  }

  Future<void> switchDevice(String deviceId) async {
    await _run(() async {
      final client = ref.read(agentClientProvider);
      await client.switchDevice(deviceId);
      ref.invalidate(audioDevicesProvider);
      // Default device id also changes -> refresh the state read.
      ref.invalidate(audioStateProvider);
    });
  }

  Future<void> _run(Future<void> Function() body) async {
    state = const AsyncValue<void>.loading();
    state = await AsyncValue.guard(body);
  }
}

final audioControllerProvider =
    AsyncNotifierProvider<AudioController, void>(AudioController.new);
