import 'dart:async';

import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../services/agent_client.dart';
import '../services/event_stream.dart';
import 'audio_provider.dart';
import 'connection_provider.dart';
import 'files_provider.dart';
import 'storage_provider.dart';

/// Builds the WebSocket URI for `/api/events` from the currently selected
/// agent address. No auth: the LAN-only deployment trusts every client.
Uri? _buildEventsUri(String? rawAddress) {
  if (rawAddress == null) return null;
  final normalizedHttp = AgentClient.normalizeBaseUrl(rawAddress);
  if (normalizedHttp == null) return null;
  // normalizedHttp looks like http://host:port (or https://...). Swap the
  // scheme to ws/wss and append the events path.
  final parsed = Uri.parse(normalizedHttp);
  final wsScheme = parsed.scheme == 'https' ? 'wss' : 'ws';
  return Uri(
    scheme: wsScheme,
    host: parsed.host,
    port: parsed.hasPort ? parsed.port : null,
    path: '/api/events',
  );
}

/// Override seam for tests: provide a custom [EventStreamConnector] that
/// short-circuits the real WebSocket layer.
final eventStreamConnectorProvider = Provider<EventStreamConnector>(
  (ref) => defaultEventStreamConnector,
);

/// Owns a single [EventStream] tied to the currently saved address. Recreated
/// when the address changes (Riverpod handles that automatically because we
/// `ref.watch` the address futures below).
///
/// Returns `null` while we don't yet have a usable address — callers should
/// treat that as "no realtime connection".
final eventStreamControllerProvider = Provider<EventStream?>((ref) {
  final saved = ref.watch(savedAddressProvider).asData?.value;
  final fallback = ref.watch(agentAddressProvider);
  // Prefer the persisted address; fall back to the form provider value
  // (which itself defaults to [defaultAgentAddress]).
  final address = saved ?? fallback;
  final uri = _buildEventsUri(address);
  if (uri == null) return null;

  final connector = ref.watch(eventStreamConnectorProvider);
  final stream = EventStream(
    uri: uri,
    connector: connector,
    onReconnected: () {
      // Upper layer re-fetches authoritative state after a reconnect so the
      // UI cannot drift from the server during a transient outage.
      ref.invalidate(audioStateProvider);
      ref.invalidate(audioDevicesProvider);
    },
  );
  stream.start();
  ref.onDispose(stream.dispose);
  return stream;
});

/// `StreamProvider<EventEnvelope>` — UI/widgets `ref.watch` this to react to
/// realtime events. Yields nothing while the controller is null (e.g. before
/// an address is set).
final eventStreamProvider = StreamProvider<EventEnvelope>((ref) {
  final controller = ref.watch(eventStreamControllerProvider);
  if (controller == null) {
    return const Stream<EventEnvelope>.empty();
  }
  return controller.stream;
});

/// Selective invalidation router. Activate via `ref.watch(eventBusProvider)`
/// from a widget that lives for the app's lifetime (e.g. the root scaffold)
/// so the listener stays subscribed.
///
/// Routing rules (mirrors server-side `EventTypes`):
/// - `audio.state` → invalidate `audioStateProvider`
/// - `audio.device.changed` → invalidate `audioDevicesProvider`
/// - `file.*` → invalidate the affected file listing.
final eventBusProvider = Provider<void>((ref) {
  ref.listen<AsyncValue<EventEnvelope>>(eventStreamProvider, (_, next) {
    final envelope = next.asData?.value;
    if (envelope == null) return;
    _routeEvent(ref, envelope);
  });
});

void _routeEvent(Ref ref, EventEnvelope envelope) {
  switch (envelope.type) {
    case 'audio.state':
      ref.invalidate(audioStateProvider);
      break;
    case 'audio.device.changed':
      ref.invalidate(audioDevicesProvider);
      // The default-device id can change as a side-effect of a device list
      // update, so refresh the state read too.
      ref.invalidate(audioStateProvider);
      break;
    default:
      if (envelope.type.startsWith('file.')) {
        // File events: invalidate the affected listing if we can read the
        // (rootId, parent path) from the payload; otherwise the bus quietly
        // skips so the broadcast cannot crash the app.
        final payload = envelope.payload;
        final rootId = payload['rootId'];
        final path = payload['path'];
        if (rootId is String && path is String) {
          ref.invalidate(fileListingProvider((rootId, _parentOfPath(path))));
        }
        if (envelope.type == 'file.moved' ||
            envelope.type == 'file.renamed') {
          final newPath = payload['newPath'];
          if (rootId is String && newPath is String) {
            ref.invalidate(
              fileListingProvider((rootId, _parentOfPath(newPath))),
            );
          }
        }
      }
  }
}

String _parentOfPath(String path) {
  if (path.isEmpty) return '';
  final normalized =
      path.replaceAll('\\', '/').replaceAll(RegExp(r'/+$'), '');
  final idx = normalized.lastIndexOf('/');
  if (idx <= 0) return '';
  return normalized.substring(0, idx);
}
