import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:maimai_home_mobile/pages/audio_page.dart';
import 'package:maimai_home_mobile/services/agent_client.dart';
import 'package:maimai_home_mobile/state/audio_provider.dart';
import 'package:mocktail/mocktail.dart';

class _MockAgentClient extends Mock implements AgentClient {}

const _state = AudioState(
  masterVolume: 0.4,
  muted: false,
  defaultDeviceId: 'dev-1',
);

const _devices = <AudioDevice>[
  AudioDevice(id: 'dev-1', name: 'Speakers', isDefault: true, state: 'active'),
  AudioDevice(id: 'dev-2', name: 'Headphones', isDefault: false, state: 'active'),
];

Widget _wrap(AgentClient client) {
  return ProviderScope(
    overrides: [
      agentClientProvider.overrideWithValue(client),
    ],
    child: const MaterialApp(home: AudioPage()),
  );
}

void main() {
  setUpAll(() {
    registerFallbackValue(0.0);
  });

  testWidgets('renders default device name and current volume after load',
      (tester) async {
    final client = _MockAgentClient();
    when(() => client.fetchAudioState()).thenAnswer((_) async => _state);
    when(() => client.fetchAudioDevices())
        .thenAnswer((_) async => _devices);

    await tester.pumpWidget(_wrap(client));
    // Resolve both FutureProviders.
    await tester.pumpAndSettle();

    // Default device name appears in the header.
    expect(find.text('Speakers'), findsWidgets);
    // Slider is present.
    expect(find.byType(Slider), findsOneWidget);
    final slider = tester.widget<Slider>(find.byType(Slider));
    expect(slider.value, closeTo(0.4, 1e-9));
  });

  testWidgets('slider onChangeEnd calls AgentClient.setVolume once',
      (tester) async {
    final client = _MockAgentClient();
    when(() => client.fetchAudioState()).thenAnswer((_) async => _state);
    when(() => client.fetchAudioDevices())
        .thenAnswer((_) async => _devices);
    when(() => client.setVolume(any())).thenAnswer((_) async => _state);

    await tester.pumpWidget(_wrap(client));
    await tester.pumpAndSettle();

    final slider = tester.widget<Slider>(find.byType(Slider));
    // Simulate a complete drag: only onChangeEnd should hit the API.
    slider.onChanged?.call(0.55);
    slider.onChanged?.call(0.6);
    slider.onChanged?.call(0.62);
    await tester.pump();
    verifyNever(() => client.setVolume(any()));

    slider.onChangeEnd?.call(0.62);
    await tester.pump();
    verify(() => client.setVolume(0.62)).called(1);
  });

  testWidgets('mute toggle calls AgentClient.setMute', (tester) async {
    final client = _MockAgentClient();
    when(() => client.fetchAudioState()).thenAnswer((_) async => _state);
    when(() => client.fetchAudioDevices())
        .thenAnswer((_) async => _devices);
    when(() => client.setMute(any()))
        .thenAnswer((_) async => _state.copyWith(muted: true));

    await tester.pumpWidget(_wrap(client));
    await tester.pumpAndSettle();

    await tester.tap(find.byKey(const Key('audio-mute-toggle')));
    await tester.pump();

    verify(() => client.setMute(true)).called(1);
  });

  testWidgets('tapping a non-default device calls switchDevice',
      (tester) async {
    final client = _MockAgentClient();
    when(() => client.fetchAudioState()).thenAnswer((_) async => _state);
    when(() => client.fetchAudioDevices())
        .thenAnswer((_) async => _devices);
    when(() => client.switchDevice(any())).thenAnswer((_) async {});

    await tester.pumpWidget(_wrap(client));
    await tester.pumpAndSettle();

    await tester.tap(find.text('Headphones'));
    await tester.pump();

    verify(() => client.switchDevice('dev-2')).called(1);
  });

  testWidgets('error from /api/audio/state shows retry button', (tester) async {
    final client = _MockAgentClient();
    var callCount = 0;
    when(() => client.fetchAudioState()).thenAnswer((_) async {
      callCount += 1;
      if (callCount == 1) {
        throw const AgentClientException(AgentException.busy, 'queue full');
      }
      return _state;
    });
    when(() => client.fetchAudioDevices())
        .thenAnswer((_) async => _devices);

    await tester.pumpWidget(_wrap(client));
    await tester.pumpAndSettle();

    expect(find.text('服务忙，请稍后重试'), findsOneWidget);
    final retry = find.widgetWithText(FilledButton, '重试');
    expect(retry, findsOneWidget);

    await tester.tap(retry);
    await tester.pumpAndSettle();

    expect(find.text('Speakers'), findsWidgets);
    expect(callCount, 2);
  });

  testWidgets('502 device_unavailable surfaces 设备不可用 message',
      (tester) async {
    final client = _MockAgentClient();
    when(() => client.fetchAudioState()).thenThrow(
      const AgentClientException(AgentException.deviceUnavailable, ''),
    );
    when(() => client.fetchAudioDevices())
        .thenAnswer((_) async => _devices);

    await tester.pumpWidget(_wrap(client));
    await tester.pumpAndSettle();

    expect(find.text('设备不可用'), findsOneWidget);
  });
}
