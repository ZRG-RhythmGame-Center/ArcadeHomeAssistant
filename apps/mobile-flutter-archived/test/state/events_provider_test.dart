import 'dart:async';

import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:maimai_home_mobile/services/agent_client.dart';
import 'package:maimai_home_mobile/services/event_stream.dart';
import 'package:maimai_home_mobile/state/audio_provider.dart';
import 'package:maimai_home_mobile/state/events_provider.dart';
import 'package:maimai_home_mobile/state/files_provider.dart';
import 'package:mocktail/mocktail.dart';

class _MockAgentClient extends Mock implements AgentClient {}

class _StubConnection {
  _StubConnection() {
    ready = readyCompleter.future;
  }

  final controller = StreamController<dynamic>.broadcast();
  final readyCompleter = Completer<void>();
  late final Future<void> ready;
}

void main() {
  late _MockAgentClient client;
  late _StubConnection connection;
  late EventStreamConnector connector;

  setUp(() {
    client = _MockAgentClient();
    connection = _StubConnection();
    var connectCount = 0;
    connector = (Uri uri) {
      // Re-arm a fresh stub on every reconnect attempt so tests that drive
      // multiple connect cycles still work.
      if (connectCount > 0) {
        connection = _StubConnection();
      }
      connectCount++;
      final c = connection;
      return EventStreamConnection(
        stream: c.controller.stream,
        ready: c.ready,
        close: () async {
          if (!c.controller.isClosed) {
            await c.controller.close();
          }
        },
      );
    };

    when(() => client.fetchAudioState()).thenAnswer(
      (_) async => const AudioState(
        masterVolume: 0.5,
        muted: false,
        defaultDeviceId: 'dev-1',
      ),
    );
    when(() => client.fetchAudioDevices()).thenAnswer(
      (_) async => const [
        AudioDevice(
          id: 'dev-1',
          name: 'Speakers',
          isDefault: true,
          state: 'active',
        ),
      ],
    );
  });

  ProviderContainer makeContainer() {
    final container = ProviderContainer(overrides: [
      agentClientProvider.overrideWithValue(client),
      eventStreamConnectorProvider.overrideWithValue(connector),
    ]);
    addTearDown(container.dispose);
    return container;
  }

  test('eventStreamProvider yields decoded envelopes', () async {
    final container = makeContainer();

    // Activate the controller (and thus the connector).
    expect(container.read(eventStreamControllerProvider), isNotNull);

    // Subscribe to the stream BEFORE we publish any event so we don't
    // miss the first frame.
    final received = <EventEnvelope>[];
    final sub = container.listen<AsyncValue<EventEnvelope>>(
      eventStreamProvider,
      (_, next) {
        final value = next.asData?.value;
        if (value != null) received.add(value);
      },
      fireImmediately: true,
    );
    addTearDown(sub.close);

    connection.readyCompleter.complete();
    connection.controller.add(
      '{"type":"audio.state","payload":{"masterVolume":0.7,"muted":false},'
      '"timestamp":"2026-05-31T10:00:00Z"}',
    );

    // Let the stream propagate.
    await Future<void>.delayed(Duration.zero);
    await Future<void>.delayed(Duration.zero);

    expect(received, hasLength(1));
    expect(received[0].type, 'audio.state');
  });

  test('audio.state event invalidates audioStateProvider', () async {
    final container = makeContainer();
    container.read(eventBusProvider);

    // Prime the audio state read so we can see invalidation cause a refetch.
    await container.read(audioStateProvider.future);
    expect(verify(() => client.fetchAudioState()).callCount, 1);

    // Fire the event.
    connection.readyCompleter.complete();
    connection.controller.add(
      '{"type":"audio.state","payload":{"masterVolume":0.9,"muted":true},'
      '"timestamp":"2026-05-31T10:00:01Z"}',
    );

    // Let event delivery and invalidation complete.
    await Future<void>.delayed(Duration.zero);
    await Future<void>.delayed(Duration.zero);
    await container.read(audioStateProvider.future);

    expect(verify(() => client.fetchAudioState()).callCount, 1,
        reason: 'second fetch caused by invalidation');
  });

  test('audio.device.changed event invalidates audioDevicesProvider',
      () async {
    final container = makeContainer();
    container.read(eventBusProvider);

    await container.read(audioDevicesProvider.future);
    expect(verify(() => client.fetchAudioDevices()).callCount, 1);

    connection.readyCompleter.complete();
    connection.controller.add(
      '{"type":"audio.device.changed","payload":{"devices":[]},'
      '"timestamp":"2026-05-31T10:00:02Z"}',
    );

    await Future<void>.delayed(Duration.zero);
    await Future<void>.delayed(Duration.zero);
    await container.read(audioDevicesProvider.future);

    expect(verify(() => client.fetchAudioDevices()).callCount, 1,
        reason: 'second fetch caused by invalidation');
  });

  test('unknown event types do not blow up the bus', () async {
    final container = makeContainer();
    container.read(eventBusProvider);

    connection.readyCompleter.complete();
    connection.controller.add(
      '{"type":"file.created","payload":{"rootId":"docs","path":"a.txt"},'
      '"timestamp":"2026-05-31T10:00:03Z"}',
    );
    connection.controller.add(
      '{"type":"made.up.event","payload":{},'
      '"timestamp":"2026-05-31T10:00:04Z"}',
    );

    await Future<void>.delayed(Duration.zero);
    await Future<void>.delayed(Duration.zero);

    // No exception means the bus tolerates unknown / future event types.
    expect(true, isTrue);
  });

  test('file.created event invalidates the parent fileListingProvider',
      () async {
    when(() => client.fetchFileRoots()).thenAnswer((_) async => const []);
    when(() => client.fetchFiles(any(), any(), limit: any(named: 'limit')))
        .thenAnswer((_) async => const FileListingResult(
              entries: [],
              total: 0,
              truncated: false,
            ));

    final container = ProviderContainer(overrides: [
      agentClientProvider.overrideWithValue(client),
      fileApiClientProvider.overrideWithValue(client),
      eventStreamConnectorProvider.overrideWithValue(connector),
    ]);
    addTearDown(container.dispose);
    container.read(eventBusProvider);

    // Keep the listing alive so invalidation triggers a refetch.
    final sub = container.listen(
      fileListingProvider(('docs', 'incoming')),
      (_, _) {},
    );
    addTearDown(sub.close);

    await container.read(fileListingProvider(('docs', 'incoming')).future);
    expect(
      verify(() => client.fetchFiles('docs', 'incoming',
          limit: any(named: 'limit'))).callCount,
      1,
    );

    connection.readyCompleter.complete();
    connection.controller.add(
      '{"type":"file.created","payload":{"rootId":"docs","path":"incoming/new.txt"},'
      '"timestamp":"2026-05-31T10:00:05Z"}',
    );

    await Future<void>.delayed(Duration.zero);
    await Future<void>.delayed(Duration.zero);
    await container.read(fileListingProvider(('docs', 'incoming')).future);

    expect(
      verify(() => client.fetchFiles('docs', 'incoming',
          limit: any(named: 'limit'))).callCount,
      1,
      reason: 'second fetch caused by invalidation',
    );
  });
}
