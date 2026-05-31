import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:maimai_home_mobile/services/agent_client.dart';
import 'package:mocktail/mocktail.dart';

class _MockDio extends Mock implements Dio {}

class _FakeOptions extends Fake implements Options {}

void main() {
  setUpAll(() {
    registerFallbackValue(_FakeOptions());
  });

  group('AgentClient.normalizeBaseUrl', () {
    test('prepends http:// when scheme is missing', () {
      expect(
        AgentClient.normalizeBaseUrl('192.168.1.100:8765'),
        'http://192.168.1.100:8765',
      );
    });

    test('preserves http:// scheme', () {
      expect(
        AgentClient.normalizeBaseUrl('http://192.168.1.100:8765'),
        'http://192.168.1.100:8765',
      );
    });

    test('preserves https:// scheme', () {
      expect(
        AgentClient.normalizeBaseUrl('https://example-host:8765'),
        'https://example-host:8765',
      );
    });

    test('returns null for empty input', () {
      expect(AgentClient.normalizeBaseUrl(''), isNull);
      expect(AgentClient.normalizeBaseUrl('   '), isNull);
    });

    test('returns null for unparseable host', () {
      expect(AgentClient.normalizeBaseUrl('http://'), isNull);
    });
  });

  group('AgentClient.fetchStatus', () {
    late _MockDio dio;
    late AgentClient client;

    setUp(() {
      dio = _MockDio();
      client = AgentClient(baseUrl: '192.168.1.100:8765', dio: dio);
    });

    test('returns AgentStatus on 200 response', () async {
      when(() => dio.get<Map<String, dynamic>>(
            any(),
            options: any(named: 'options'),
          )).thenAnswer((_) async => Response<Map<String, dynamic>>(
            requestOptions: RequestOptions(path: '/api/status'),
            statusCode: 200,
            data: {
              'machineName': 'test-pc',
              'version': '1.2.3',
              'uptimeSeconds': 42,
              'capabilities': {
                'audioVolume': true,
                'audioMute': false,
                'audioDeviceSwitch': true,
                'fileManagement': true,
                'discoveryBroadcast': false,
              },
            },
          ));

      final status = await client.fetchStatus();
      expect(status.machineName, 'test-pc');
      expect(status.version, '1.2.3');
      expect(status.uptimeSeconds, 42);
      expect(status.capabilities['audioVolume'], true);
      expect(status.capabilities['discoveryBroadcast'], false);
      expect(status.baseUrl, 'http://192.168.1.100:8765');
    });

    test('maps timeout DioException to AgentException.network', () async {
      when(() => dio.get<Map<String, dynamic>>(
            any(),
            options: any(named: 'options'),
          )).thenThrow(DioException(
        requestOptions: RequestOptions(path: '/api/status'),
        type: DioExceptionType.connectionTimeout,
      ));

      await expectLater(
        client.fetchStatus(),
        throwsA(isA<AgentClientException>()
            .having((e) => e.kind, 'kind', AgentException.network)),
      );
    });

    test('maps connectionError DioException to AgentException.network',
        () async {
      when(() => dio.get<Map<String, dynamic>>(
            any(),
            options: any(named: 'options'),
          )).thenThrow(DioException(
        requestOptions: RequestOptions(path: '/api/status'),
        type: DioExceptionType.connectionError,
      ));

      await expectLater(
        client.fetchStatus(),
        throwsA(isA<AgentClientException>()
            .having((e) => e.kind, 'kind', AgentException.network)),
      );
    });

    test('maps other badResponse to AgentException.unknown', () async {
      when(() => dio.get<Map<String, dynamic>>(
            any(),
            options: any(named: 'options'),
          )).thenThrow(DioException(
        requestOptions: RequestOptions(path: '/api/status'),
        type: DioExceptionType.badResponse,
        response: Response(
          requestOptions: RequestOptions(path: '/api/status'),
          statusCode: 500,
        ),
      ));

      await expectLater(
        client.fetchStatus(),
        throwsA(isA<AgentClientException>()
            .having((e) => e.kind, 'kind', AgentException.unknown)),
      );
    });

    test('maps null response data to AgentException.unknown', () async {
      when(() => dio.get<Map<String, dynamic>>(
            any(),
            options: any(named: 'options'),
          )).thenAnswer((_) async => Response<Map<String, dynamic>>(
            requestOptions: RequestOptions(path: '/api/status'),
            statusCode: 200,
            data: null,
          ));

      await expectLater(
        client.fetchStatus(),
        throwsA(isA<AgentClientException>()
            .having((e) => e.kind, 'kind', AgentException.unknown)),
      );
    });

    test('throws when baseUrl is unparseable', () async {
      final badClient = AgentClient(baseUrl: '', dio: dio);
      await expectLater(
        badClient.fetchStatus(),
        throwsA(isA<AgentClientException>()
            .having((e) => e.kind, 'kind', AgentException.unknown)),
      );
    });

    test('does not attach Authorization header (LAN-only, no auth)',
        () async {
      Options? capturedOptions;
      when(() => dio.get<Map<String, dynamic>>(
            any(),
            options: any(named: 'options'),
          )).thenAnswer((invocation) async {
        capturedOptions =
            invocation.namedArguments[#options] as Options?;
        return Response<Map<String, dynamic>>(
          requestOptions: RequestOptions(path: '/api/status'),
          statusCode: 200,
          data: {
            'machineName': 't',
            'version': 'v',
            'uptimeSeconds': 0,
            'capabilities': {},
          },
        );
      });

      await client.fetchStatus();
      expect(capturedOptions, isNotNull);
      expect(capturedOptions!.headers?['Authorization'], isNull);
    });
  });
}
