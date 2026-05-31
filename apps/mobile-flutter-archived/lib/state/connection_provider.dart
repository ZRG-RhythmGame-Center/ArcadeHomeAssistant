import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../services/agent_client.dart';

/// Sealed-style status hierarchy for the connection flow.
///
/// Using a class hierarchy with const constructors instead of `sealed` keyword
/// to keep compatibility with the project SDK constraint. Treat this as
/// closed-for-extension; pattern-match using `is`/`switch` on runtime type.
sealed class ConnectionStatus {
  const ConnectionStatus();

  const factory ConnectionStatus.idle() = Idle;
  const factory ConnectionStatus.connecting() = Connecting;
  const factory ConnectionStatus.connected(AgentStatus status) = Connected;
  const factory ConnectionStatus.error(String message, AgentException kind) =
      ConnectionError;
}

class Idle extends ConnectionStatus {
  const Idle();

  @override
  bool operator ==(Object other) => other is Idle;

  @override
  int get hashCode => 0;
}

class Connecting extends ConnectionStatus {
  const Connecting();

  @override
  bool operator ==(Object other) => other is Connecting;

  @override
  int get hashCode => 1;
}

class Connected extends ConnectionStatus {
  const Connected(this.status);

  final AgentStatus status;

  @override
  bool operator ==(Object other) =>
      other is Connected && other.status == status;

  @override
  int get hashCode => status.hashCode;
}

class ConnectionError extends ConnectionStatus {
  const ConnectionError(this.message, this.kind);

  final String message;
  final AgentException kind;

  @override
  bool operator ==(Object other) =>
      other is ConnectionError && other.message == message && other.kind == kind;

  @override
  int get hashCode => Object.hash(message, kind);
}

/// Default agent address. Matches the historical hardcoded value in main.dart
/// so that pre-Riverpod tests continue to pass.
const String defaultAgentAddress = '192.168.1.100:8765';

/// Currently selected agent address (raw user input, pre-normalization).
final agentAddressProvider = StateProvider<String>(
  (ref) => defaultAgentAddress,
);

/// Factory used by [ConnectionNotifier] to build an [AgentClient] for an
/// address. Override in tests to inject a mock Dio:
///
/// ```dart
/// agentClientFactoryProvider.overrideWithValue(
///   (baseUrl) => AgentClient(baseUrl: baseUrl, dio: mockDio),
/// );
/// ```
final agentClientFactoryProvider = Provider<AgentClient Function(String)>(
  (ref) => (String baseUrl) => AgentClient(baseUrl: baseUrl),
);

class ConnectionNotifier extends StateNotifier<ConnectionStatus> {
  ConnectionNotifier(this._buildClient) : super(const ConnectionStatus.idle());

  final AgentClient Function(String) _buildClient;

  Future<void> connect(String address) async {
    state = const ConnectionStatus.connecting();
    final client = _buildClient(address);
    try {
      final status = await client.fetchStatus();
      state = ConnectionStatus.connected(status);
    } on AgentClientException catch (error) {
      state = ConnectionStatus.error(
        AgentClient.describeError(error),
        error.kind,
      );
    } catch (error) {
      state = ConnectionStatus.error(
        '请求失败：$error',
        AgentException.unknown,
      );
    } finally {
      client.close();
    }
  }

  void reset() {
    state = const ConnectionStatus.idle();
  }
}

final connectionStateProvider =
    StateNotifierProvider<ConnectionNotifier, ConnectionStatus>((ref) {
  final factory = ref.watch(agentClientFactoryProvider);
  return ConnectionNotifier(factory);
});
