import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:maimai_home_mobile/services/agent_client.dart';
import 'package:maimai_home_mobile/state/audio_provider.dart';
import 'package:mocktail/mocktail.dart';

class _MockAgentClient extends Mock implements AgentClient {}

const _stateA = AudioState(
  masterVolume: 0.5,
  muted: false,
  defaultDeviceId: 'dev-1',
);

const _stateB = AudioState(
  masterVolume: 0.7,
  muted: false,
  defaultDeviceId: 'dev-1',
);

const _stateMuted = AudioState(
  masterVolume: 0.5,
  muted: true,
  defaultDeviceId: 'dev-1',
);

const _devicesV1 = <AudioDevice>[
  AudioDevice(id: 'dev-1', name: 'Speakers', isDefault: true, state: 'active'),
  AudioDevice(id: 'dev-2', name: 'Headphones', isDefault: false, state: 'active'),
];

const _devicesV2 = <AudioDevice>[
  AudioDevice(id: 'dev-1', name: 'Speakers', isDefault: false, state: 'active'),
  AudioDevice(id: 'dev-2', name: 'Headphones', isDefault: true, state: 'active'),
];

ProviderContainer _container(AgentClient client) {
  return ProviderContainer(overrides: [
    agentClientProvider.overrideWithValue(client),
  ]);
}

void main() {
  group('audioStateProvider', () {
    test('reads state from AgentClient', () async {
      final client = _MockAgentClient();
      when(() => client.fetchAudioState()).thenAnswer((_) async => _stateA);

      final container = _container(client);
      addTearDown(container.dispose);

      final state = await container.read(audioStateProvider.future);
      expect(state, _stateA);
      verify(() => client.fetchAudioState()).called(1);
    });
  });

  group('audioDevicesProvider', () {
    test('reads devices from AgentClient', () async {
      final client = _MockAgentClient();
      when(() => client.fetchAudioDevices())
          .thenAnswer((_) async => _devicesV1);

      final container = _container(client);
      addTearDown(container.dispose);

      final devices = await container.read(audioDevicesProvider.future);
      expect(devices, _devicesV1);
    });
  });

  group('audioControllerProvider', () {
    test('setVolume calls AgentClient and invalidates audioStateProvider',
        () async {
      final client = _MockAgentClient();
      var stateCalls = 0;
      when(() => client.fetchAudioState()).thenAnswer((_) async {
        stateCalls += 1;
        return stateCalls == 1 ? _stateA : _stateB;
      });
      when(() => client.setVolume(any())).thenAnswer((_) async => _stateB);

      final container = _container(client);
      addTearDown(container.dispose);

      // Subscribe so invalidation triggers a rebuild.
      final sub = container.listen<AsyncValue<AudioState>>(
        audioStateProvider,
        (_, __) {},
      );
      addTearDown(sub.close);

      await container.read(audioStateProvider.future);
      expect(stateCalls, 1);

      await container
          .read(audioControllerProvider.notifier)
          .setVolume(0.7);

      verify(() => client.setVolume(0.7)).called(1);

      // Invalidation refires the build; read .future to await refetch.
      final after = await container.read(audioStateProvider.future);
      expect(after, _stateB);
      expect(stateCalls, greaterThanOrEqualTo(2));
    });

    test('setMute calls AgentClient and invalidates audioStateProvider',
        () async {
      final client = _MockAgentClient();
      var stateCalls = 0;
      when(() => client.fetchAudioState()).thenAnswer((_) async {
        stateCalls += 1;
        return stateCalls == 1 ? _stateA : _stateMuted;
      });
      when(() => client.setMute(any())).thenAnswer((_) async => _stateMuted);

      final container = _container(client);
      addTearDown(container.dispose);

      final sub = container.listen<AsyncValue<AudioState>>(
        audioStateProvider,
        (_, __) {},
      );
      addTearDown(sub.close);

      await container.read(audioStateProvider.future);
      expect(stateCalls, 1);

      await container
          .read(audioControllerProvider.notifier)
          .setMute(true);

      verify(() => client.setMute(true)).called(1);

      final after = await container.read(audioStateProvider.future);
      expect(after.muted, isTrue);
      expect(stateCalls, greaterThanOrEqualTo(2));
    });

    test('switchDevice calls AgentClient and invalidates audioDevicesProvider',
        () async {
      final client = _MockAgentClient();
      var devicesCalls = 0;
      when(() => client.fetchAudioDevices()).thenAnswer((_) async {
        devicesCalls += 1;
        return devicesCalls == 1 ? _devicesV1 : _devicesV2;
      });
      when(() => client.fetchAudioState()).thenAnswer((_) async => _stateA);
      when(() => client.switchDevice(any())).thenAnswer((_) async {});

      final container = _container(client);
      addTearDown(container.dispose);

      final sub = container.listen<AsyncValue<List<AudioDevice>>>(
        audioDevicesProvider,
        (_, __) {},
      );
      addTearDown(sub.close);

      await container.read(audioDevicesProvider.future);
      expect(devicesCalls, 1);

      await container
          .read(audioControllerProvider.notifier)
          .switchDevice('dev-2');

      verify(() => client.switchDevice('dev-2')).called(1);

      final after = await container.read(audioDevicesProvider.future);
      expect(after.firstWhere((d) => d.isDefault).id, 'dev-2');
      expect(devicesCalls, greaterThanOrEqualTo(2));
    });

    test('setVolume surfaces AgentClientException as AsyncError', () async {
      final client = _MockAgentClient();
      when(() => client.fetchAudioState()).thenAnswer((_) async => _stateA);
      when(() => client.setVolume(any())).thenThrow(
        const AgentClientException(AgentException.busy, 'busy'),
      );

      final container = _container(client);
      addTearDown(container.dispose);

      await container
          .read(audioControllerProvider.notifier)
          .setVolume(0.4);

      final state = container.read(audioControllerProvider);
      expect(state, isA<AsyncError<void>>());
      final error = state.error;
      expect(error, isA<AgentClientException>());
      expect((error as AgentClientException).kind, AgentException.busy);
    });
  });
}
