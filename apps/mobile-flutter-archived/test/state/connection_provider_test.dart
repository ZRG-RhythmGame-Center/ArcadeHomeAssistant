import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:maimai_home_mobile/services/agent_client.dart';
import 'package:maimai_home_mobile/state/connection_provider.dart';
import 'package:mocktail/mocktail.dart';

class _MockDio extends Mock implements Dio {}

class _FakeOptions extends Fake implements Options {}

ProviderContainer _container(Dio dio) {
  return ProviderContainer(overrides: [
    agentClientFactoryProvider.overrideWithValue((baseUrl) {
      return AgentClient(baseUrl: baseUrl, dio: dio);
    }),
  ]);
}

void main() {
  setUpAll(() {
    registerFallbackValue(_FakeOptions());
  });

  group('connectionStateProvider', () {
    test('initial state is idle', () {
      final container = ProviderContainer();
      addTearDown(container.dispose);
      expect(container.read(connectionStateProvider), const ConnectionStatus.idle());
    });

    test('connect() transitions idle -> connecting -> connected on success',
        () async {
      final dio = _MockDio();
      when(() => dio.get<Map<String, dynamic>>(
            any(),
            options: any(named: 'options'),
          )).thenAnswer((_) async => Response<Map<String, dynamic>>(
            requestOptions: RequestOptions(path: '/api/status'),
            statusCode: 200,
            data: {
              'machineName': 'test-pc',
              'version': '1.0.0',
              'uptimeSeconds': 10,
              'capabilities': {
                'audioVolume': true,
                'audioMute': true,
                'audioDeviceSwitch': true,
                'fileManagement': true,
                'discoveryBroadcast': true,
              },
            },
          ));

      final container = _container(dio);
      addTearDown(container.dispose);

      final captured = <ConnectionStatus>[];
      final sub = container.listen<ConnectionStatus>(
        connectionStateProvider,
        (prev, next) => captured.add(next),
      );
      addTearDown(sub.close);

      await container
          .read(connectionStateProvider.notifier)
          .connect('192.168.1.100:8765');

      expect(captured.first, const ConnectionStatus.connecting());
      final last = container.read(connectionStateProvider);
      expect(last, isA<Connected>());
      final connected = last as Connected;
      expect(connected.status.machineName, 'test-pc');
    });

    test('connect() transitions to error on 401', () async {
      final dio = _MockDio();
      when(() => dio.get<Map<String, dynamic>>(
            any(),
            options: any(named: 'options'),
          )).thenThrow(DioException(
        requestOptions: RequestOptions(path: '/api/status'),
        type: DioExceptionType.badResponse,
        response: Response(
          requestOptions: RequestOptions(path: '/api/status'),
          statusCode: 401,
        ),
      ));

      final container = _container(dio);
      addTearDown(container.dispose);

      await container
          .read(connectionStateProvider.notifier)
          .connect('192.168.1.100:8765');

      final state = container.read(connectionStateProvider);
      expect(state, isA<ConnectionError>());
      final error = state as ConnectionError;
      expect(error.kind, AgentException.unauthorized);
    });

    test('connect() rejects empty/invalid address with unknown error',
        () async {
      final dio = _MockDio();
      final container = _container(dio);
      addTearDown(container.dispose);

      await container
          .read(connectionStateProvider.notifier)
          .connect('');

      final state = container.read(connectionStateProvider);
      expect(state, isA<ConnectionError>());
    });

    test('connect() maps timeout to network error', () async {
      final dio = _MockDio();
      when(() => dio.get<Map<String, dynamic>>(
            any(),
            options: any(named: 'options'),
          )).thenThrow(DioException(
        requestOptions: RequestOptions(path: '/api/status'),
        type: DioExceptionType.connectionTimeout,
      ));

      final container = _container(dio);
      addTearDown(container.dispose);

      await container
          .read(connectionStateProvider.notifier)
          .connect('192.168.1.100:8765');

      final state = container.read(connectionStateProvider);
      expect(state, isA<ConnectionError>());
      expect((state as ConnectionError).kind, AgentException.network);
    });
  });

  group('agentAddressProvider', () {
    test('default value is 192.168.1.100:8765', () {
      final container = ProviderContainer();
      addTearDown(container.dispose);
      expect(container.read(agentAddressProvider), '192.168.1.100:8765');
    });

    test('can be updated', () {
      final container = ProviderContainer();
      addTearDown(container.dispose);
      container.read(agentAddressProvider.notifier).state = '10.0.0.1:8765';
      expect(container.read(agentAddressProvider), '10.0.0.1:8765');
    });
  });
}
