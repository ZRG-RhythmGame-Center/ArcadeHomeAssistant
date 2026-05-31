import 'dart:async';
import 'dart:convert';

import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:nsd/nsd.dart' as nsd;

const String discoveryServiceType = '_maimai-home._tcp';

/// One agent surfaced by mDNS / DNS-SD discovery.
class DiscoveredAgent {
  const DiscoveredAgent({
    required this.id,
    required this.name,
    required this.host,
    required this.port,
    required this.version,
  });

  final String id;
  final String name;
  final String host;
  final int port;
  final String version;

  String get connectAddress => '$host:$port';

  factory DiscoveredAgent.fromService(nsd.Service service) {
    final host = _extractHost(service);
    final port = service.port ?? 0;

    String? readTxt(String key) {
      final bytes = service.txt?[key];
      if (bytes == null || bytes.isEmpty) {
        return null;
      }
      try {
        return utf8.decode(bytes);
      } catch (_) {
        return null;
      }
    }

    return DiscoveredAgent(
      id: '${service.name ?? 'unknown'}@$host:$port',
      name: readTxt('name') ?? service.name ?? host,
      host: host,
      port: port,
      version: readTxt('version') ?? 'unknown',
    );
  }

  static String _extractHost(nsd.Service service) {
    final host = service.host;
    if (host != null && host.isNotEmpty) {
      return host;
    }
    final addresses = service.addresses;
    if (addresses != null && addresses.isNotEmpty) {
      return addresses.first.address;
    }
    return service.name ?? 'unknown';
  }
}

/// State for the discovery flow.
class DiscoveryState {
  const DiscoveryState({
    this.isDiscovering = false,
    this.agents = const [],
    this.errorMessage,
  });

  final bool isDiscovering;
  final List<DiscoveredAgent> agents;
  final String? errorMessage;

  DiscoveryState copyWith({
    bool? isDiscovering,
    List<DiscoveredAgent>? agents,
    String? errorMessage,
    bool clearError = false,
  }) {
    return DiscoveryState(
      isDiscovering: isDiscovering ?? this.isDiscovering,
      agents: agents ?? this.agents,
      errorMessage: clearError ? null : (errorMessage ?? this.errorMessage),
    );
  }
}

/// Adapter so tests can swap out the real `nsd` package for an in-memory fake.
abstract class DiscoveryBackend {
  Future<nsd.Discovery> startDiscovery(String serviceType);
  Future<void> stopDiscovery(nsd.Discovery discovery);
  Future<nsd.Service> resolve(nsd.Service service);
}

class _NsdBackend implements DiscoveryBackend {
  const _NsdBackend();

  @override
  Future<nsd.Discovery> startDiscovery(String serviceType) =>
      nsd.startDiscovery(serviceType);

  @override
  Future<void> stopDiscovery(nsd.Discovery discovery) =>
      nsd.stopDiscovery(discovery);

  @override
  Future<nsd.Service> resolve(nsd.Service service) => nsd.resolve(service);
}

final discoveryBackendProvider = Provider<DiscoveryBackend>(
  (ref) => const _NsdBackend(),
);

class DiscoveryNotifier extends StateNotifier<DiscoveryState> {
  DiscoveryNotifier(this._backend) : super(const DiscoveryState());

  final DiscoveryBackend _backend;

  nsd.Discovery? _discovery;
  nsd.ServiceListener? _serviceListener;
  Timer? _timeout;

  /// Start a fresh scan. Cancels any in-flight scan first and clears the list.
  Future<void> start() async {
    await _stop(clearList: true);

    state = state.copyWith(isDiscovering: true, clearError: true);

    try {
      final discovery = await _backend.startDiscovery(discoveryServiceType);
      _discovery = discovery;

      Future<void> handle(nsd.Service service, nsd.ServiceStatus status) async {
        if (status != nsd.ServiceStatus.found) {
          return;
        }
        try {
          final resolved = await _backend.resolve(service);
          final agent = DiscoveredAgent.fromService(resolved);
          if (!mounted) {
            return;
          }
          final next = [...state.agents];
          final existing = next.indexWhere((item) => item.id == agent.id);
          if (existing >= 0) {
            next[existing] = agent;
          } else {
            next.add(agent);
          }
          next.sort((a, b) => a.name.compareTo(b.name));
          state = state.copyWith(agents: next);
        } catch (_) {
          // 跳过单个解析失败的服务，不影响其余发现结果
        }
      }

      _serviceListener = handle;
      discovery.addServiceListener(handle);

      _timeout?.cancel();
      _timeout = Timer(const Duration(seconds: 6), () async {
        if (mounted) {
          await _stop();
        }
      });
    } catch (error) {
      if (!mounted) {
        return;
      }
      state = state.copyWith(
        isDiscovering: false,
        errorMessage: '扫描失败：$error',
      );
    }
  }

  Future<void> stop() => _stop();

  Future<void> _stop({bool clearList = false}) async {
    _timeout?.cancel();
    _timeout = null;

    final discovery = _discovery;
    final listener = _serviceListener;
    _discovery = null;
    _serviceListener = null;

    if (discovery != null && listener != null) {
      try {
        discovery.removeServiceListener(listener);
      } catch (_) {
        // 已经被释放，忽略
      }
    }
    if (discovery != null) {
      try {
        await _backend.stopDiscovery(discovery);
      } catch (_) {
        // 已经停止或释放过的发现忽略错误
      }
    }

    if (mounted) {
      state = state.copyWith(
        isDiscovering: false,
        agents: clearList ? const [] : state.agents,
      );
    }
  }

  @override
  void dispose() {
    _timeout?.cancel();
    _timeout = null;
    final discovery = _discovery;
    final listener = _serviceListener;
    _discovery = null;
    _serviceListener = null;
    if (discovery != null && listener != null) {
      try {
        discovery.removeServiceListener(listener);
      } catch (_) {}
    }
    if (discovery != null) {
      // fire-and-forget; dispose must be sync
      unawaited(_backend.stopDiscovery(discovery).catchError((_) {}));
    }
    super.dispose();
  }
}

/// Notifier-based provider for the discovery flow.
///
/// Spec calls for a `StreamProvider<List<DiscoveredAgent>>`, but the page
/// needs imperative `start()`/`stop()` controls (button-driven scan with a
/// 6-second auto-timeout). A `StateNotifierProvider` exposing both the list
/// and the discovery state captures the same data plus the controls in one
/// place; consumers can `select` the `agents` field if they only need the
/// list. See [discoveryListProvider] for a list-only convenience view.
final discoveryProvider =
    StateNotifierProvider<DiscoveryNotifier, DiscoveryState>((ref) {
  final backend = ref.watch(discoveryBackendProvider);
  return DiscoveryNotifier(backend);
});

/// Convenience: just the list of agents from [discoveryProvider].
final discoveryListProvider = Provider<List<DiscoveredAgent>>(
  (ref) => ref.watch(discoveryProvider).agents,
);
