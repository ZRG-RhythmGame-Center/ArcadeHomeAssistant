import 'dart:async';
import 'dart:convert';

import 'package:web_socket_channel/web_socket_channel.dart';

/// Realtime envelope mirroring the server-side `Realtime/EventEnvelope.cs`
/// record. Wire shape:
///
/// ```json
/// {
///   "type": "audio.state",
///   "payload": {...},
///   "timestamp": "2026-05-31T10:00:00Z"
/// }
/// ```
class EventEnvelope {
  const EventEnvelope({
    required this.type,
    required this.payload,
    required this.timestamp,
  });

  /// Event family, e.g. `audio.state`, `audio.device.changed`,
  /// `file.created`, `file.deleted`, `file.renamed`, `file.moved`.
  final String type;

  /// Free-form JSON object — shape depends on [type].
  final Map<String, dynamic> payload;

  /// ISO-8601 string from the server. Kept as String to avoid forcing a
  /// specific local representation on the UI layer.
  final String timestamp;

  /// Returns `null` if [json] is missing required fields. Logging /
  /// surfacing the malformed frame is the caller's responsibility.
  static EventEnvelope? tryFromJson(Map<String, dynamic> json) {
    final type = json['type'];
    final payload = json['payload'];
    final timestamp = json['timestamp'];
    if (type is! String || timestamp is! String) {
      return null;
    }
    final payloadMap = payload is Map<String, dynamic>
        ? payload
        : (payload is Map ? Map<String, dynamic>.from(payload) : null);
    if (payloadMap == null) {
      return null;
    }
    return EventEnvelope(
      type: type,
      payload: payloadMap,
      timestamp: timestamp,
    );
  }
}

/// Single attempt at a WebSocket connection, abstracted so tests can swap in
/// a fake connector without spinning up a real socket.
///
/// - [stream] yields decoded text frames (or raw payloads); when it `onDone`s
///   the [EventStream] treats the connection as terminated.
/// - [ready] resolves once the underlying transport reports the handshake
///   completed; tests use it to distinguish "connected then closed" from
///   "connect failed". Production [defaultConnector] resolves it lazily on
///   the first frame received OR when the stream completes — both are
///   indistinguishable for our purposes since the [web_socket_channel]
///   package surfaces the open state via the stream itself.
/// - [close] gracefully tears the connection down.
class EventStreamConnection {
  EventStreamConnection({
    required this.stream,
    required this.ready,
    required this.close,
  });

  final Stream<dynamic> stream;
  final Future<void> ready;
  final Future<void> Function() close;
}

/// Function signature for the connector seam. Production uses
/// [defaultEventStreamConnector]; tests provide deterministic fakes.
typedef EventStreamConnector = EventStreamConnection Function(Uri uri);

/// Production connector: opens a real [WebSocketChannel].
EventStreamConnection defaultEventStreamConnector(Uri uri) {
  final channel = WebSocketChannel.connect(uri);
  return EventStreamConnection(
    stream: channel.stream,
    // For real WebSockets we don't have an explicit "open" callback; treat
    // the first frame OR the stream completing as the signal that we have
    // reached either a connected or failed state. The reset-on-success
    // semantics are still preserved because we only mark the connection
    // "alive" in [EventStream] after at least one frame is observed.
    ready: channel.ready,
    close: () async {
      await channel.sink.close();
    },
  );
}

/// Mobile WebSocket client with exponential-backoff reconnect.
///
/// Lifecycle:
/// 1. [start] → schedule first connect attempt.
/// 2. On successful handshake, reset the backoff to [initialBackoff].
/// 3. On stream completion (clean close OR transport failure):
///    - If we ever reached the "ready" state on this socket, fire
///      [onReconnected] on the NEXT successful reconnect (so callers can
///      re-fetch state after a transient outage). The very first connect
///      does NOT count as a reconnect.
///    - Otherwise grow the backoff (clamped at [maxBackoff]) and retry.
/// 4. [dispose] cancels the pending reconnect timer and closes the active
///    connection.
class EventStream {
  EventStream({
    required Uri uri,
    EventStreamConnector? connector,
    this.onReconnected,
    this.initialBackoff = const Duration(seconds: 1),
    this.maxBackoff = const Duration(seconds: 30),
    // Keep `uri` and `_uri` distinct so a future setter (e.g. retargeting
    // when the saved address changes) does not have to break this public
    // signature.
  // ignore: prefer_initializing_formals
  })  : _uri = uri,
        _connector = connector ?? defaultEventStreamConnector;

  final Uri _uri;
  final EventStreamConnector _connector;

  /// Fired whenever a connection is re-established AFTER at least one
  /// previous successful connection. Use this to invalidate cached state
  /// (e.g. `audioStateProvider`, `fileListingProvider`) so the UI reflects
  /// any changes that happened during the outage.
  final void Function()? onReconnected;

  /// Backoff applied after the first failure. Default: 1s.
  final Duration initialBackoff;

  /// Cap for the exponential backoff. Default: 30s.
  final Duration maxBackoff;

  final StreamController<EventEnvelope> _eventsController =
      StreamController<EventEnvelope>.broadcast();

  Timer? _reconnectTimer;
  EventStreamConnection? _activeConnection;
  StreamSubscription<dynamic>? _activeSub;

  Duration _currentBackoff = const Duration(seconds: 1);
  bool _hasEverConnected = false;

  /// Set to true once [dispose] has been called, after which further
  /// reconnect attempts are suppressed.
  bool _disposed = false;

  /// Stream of decoded events. Lifetime is tied to this [EventStream]; the
  /// controller is closed in [dispose].
  Stream<EventEnvelope> get stream => _eventsController.stream;

  /// Begin connecting. Idempotent: calling [start] more than once while a
  /// connection is active is a no-op.
  void start() {
    if (_disposed) return;
    if (_activeConnection != null || _reconnectTimer != null) return;
    _currentBackoff = initialBackoff;
    _connect();
  }

  /// Tear everything down: cancels timers, closes the active connection,
  /// closes the events stream. Safe to call multiple times.
  Future<void> dispose() async {
    if (_disposed) return;
    _disposed = true;
    _reconnectTimer?.cancel();
    _reconnectTimer = null;
    await _activeSub?.cancel();
    _activeSub = null;
    final conn = _activeConnection;
    _activeConnection = null;
    if (conn != null) {
      try {
        await conn.close();
      } catch (_) {
        // Best-effort close; nothing actionable on shutdown.
      }
    }
    if (!_eventsController.isClosed) {
      await _eventsController.close();
    }
  }

  void _scheduleConnect(Duration delay) {
    _reconnectTimer?.cancel();
    if (delay == Duration.zero) {
      // Schedule on a microtask so behavior matches the timer-based path
      // (caller cannot synchronously observe a half-built _activeConnection).
      _reconnectTimer = Timer(Duration.zero, _connect);
      return;
    }
    _reconnectTimer = Timer(delay, _connect);
  }

  void _connect() {
    _reconnectTimer = null;
    if (_disposed) return;

    final EventStreamConnection connection;
    try {
      connection = _connector(_uri);
    } catch (_) {
      _scheduleReconnectAfterFailure(connectedThisAttempt: false);
      return;
    }
    _activeConnection = connection;

    var connectedThisAttempt = false;

    // Track the "ready" signal so we can reset the backoff and detect
    // whether THIS attempt counted as a reconnect on subsequent close.
    connection.ready.then((_) {
      if (_disposed || _activeConnection != connection) return;
      connectedThisAttempt = true;
      _currentBackoff = initialBackoff;
      if (_hasEverConnected) {
        // We reached the ready state again after at least one prior success.
        try {
          onReconnected?.call();
        } catch (_) {
          // Swallow callback errors so they cannot break the reconnect loop.
        }
      }
      _hasEverConnected = true;
    }).catchError((_) {
      // ready failures fall through to onDone of the stream.
    });

    _activeSub = connection.stream.listen(
      _handleFrame,
      onError: (_, _) {
        // Errors are reported before/with onDone; the onDone branch handles
        // teardown.
      },
      onDone: () {
        if (_disposed || _activeConnection != connection) return;
        _activeConnection = null;
        _activeSub = null;
        _scheduleReconnectAfterFailure(
          connectedThisAttempt: connectedThisAttempt,
        );
      },
      cancelOnError: false,
    );
  }

  void _handleFrame(dynamic raw) {
    if (_disposed) return;
    final text = raw is String ? raw : (raw is List<int> ? utf8.decode(raw) : null);
    if (text == null) return;
    try {
      final decoded = json.decode(text);
      if (decoded is! Map) return;
      final envelope =
          EventEnvelope.tryFromJson(Map<String, dynamic>.from(decoded));
      if (envelope == null) return;
      if (!_eventsController.isClosed) {
        _eventsController.add(envelope);
      }
    } on FormatException {
      // Malformed frame — drop silently. The reconnect loop is unaffected.
    }
  }

  void _scheduleReconnectAfterFailure({required bool connectedThisAttempt}) {
    if (_disposed) return;
    final delay = connectedThisAttempt ? initialBackoff : _currentBackoff;
    _scheduleConnect(delay);
    // Compute the NEXT delay to use if THIS reconnect also fails.
    if (connectedThisAttempt) {
      // We were connected this time; the next failure starts from the
      // initial backoff. The doubling for the *following* failure happens
      // here so that two back-to-back failures still produce 1s, 2s, 4s.
      _currentBackoff = _doubleClamped(initialBackoff);
    } else {
      _currentBackoff = _doubleClamped(_currentBackoff);
    }
  }

  Duration _doubleClamped(Duration current) {
    final doubled = current * 2;
    return doubled > maxBackoff ? maxBackoff : doubled;
  }
}
