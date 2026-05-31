import 'dart:async';

import 'package:fake_async/fake_async.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:maimai_home_mobile/services/event_stream.dart';

/// Test harness for [EventStream] using a controllable fake connector.
///
/// Each connect attempt produces a fresh [_FakeConnection] whose stream and
/// `ready` future the test drives explicitly. The harness records every
/// connect call so assertions can verify backoff timing and re-connection
/// counts without touching real sockets.
class _Harness {
  final List<Uri> attempts = [];
  final List<StreamController<dynamic>> streams = [];
  final List<Completer<void>> readys = [];

  EventStreamConnection connect(Uri uri) {
    attempts.add(uri);
    final controller = StreamController<dynamic>();
    final ready = Completer<void>();
    streams.add(controller);
    readys.add(ready);
    return EventStreamConnection(
      stream: controller.stream,
      ready: ready.future,
      close: () async {
        if (!controller.isClosed) {
          await controller.close();
        }
      },
    );
  }

  /// Mark connection [index] as established, then close it (simulating a
  /// remote disconnect of an already-connected socket).
  void successThenClose(int index) {
    if (!readys[index].isCompleted) {
      readys[index].complete();
    }
  }

  void closeStream(int index) {
    final c = streams[index];
    if (!c.isClosed) {
      c.close();
    }
  }

  /// Simulate a connect failure: stream completes (onDone) before [ready]
  /// ever resolves. Callers schedule this via the fake clock to control
  /// when "the connect attempt fails".
  void failNow(int index) {
    closeStream(index);
  }
}

void main() {
  group('EventStream reconnect', () {
    test('reconnects 1s after a connected channel closes', () {
      fakeAsync((async) {
        final harness = _Harness();
        final stream = EventStream(
          uri: Uri.parse('ws://test/api/events'),
          connector: harness.connect,
        );
        stream.start();
        async.flushMicrotasks();

        // Initial connect attempt happened synchronously.
        expect(harness.attempts.length, 1);

        // Mark the connection as established.
        harness.successThenClose(0);
        async.flushMicrotasks();

        // Now the server "drops" the socket.
        harness.closeStream(0);
        async.flushMicrotasks();

        // Just before 1s: still no second attempt.
        async.elapse(const Duration(milliseconds: 999));
        async.flushMicrotasks();
        expect(harness.attempts.length, 1, reason: 'reconnect must wait 1s');

        // At 1s the reconnect fires.
        async.elapse(const Duration(milliseconds: 2));
        async.flushMicrotasks();
        expect(harness.attempts.length, 2, reason: 'reconnect at t=1s');

        stream.dispose();
      });
    });

    test('exponential backoff grows on consecutive failures', () {
      fakeAsync((async) {
        final harness = _Harness();
        final stream = EventStream(
          uri: Uri.parse('ws://test/api/events'),
          connector: harness.connect,
          // Use defaults: 1s -> 2s -> 4s -> ... -> 30s cap.
        );
        stream.start();
        async.flushMicrotasks();

        // 1st attempt at t=0. Make it fail (ready never completes,
        // stream closes immediately).
        expect(harness.attempts.length, 1);
        harness.failNow(0);
        async.flushMicrotasks();

        // After 1s: 2nd attempt.
        async.elapse(const Duration(seconds: 1));
        async.flushMicrotasks();
        expect(harness.attempts.length, 2);
        harness.failNow(1);
        async.flushMicrotasks();

        // 2s later: 3rd attempt.
        async.elapse(const Duration(seconds: 2) - const Duration(milliseconds: 1));
        async.flushMicrotasks();
        expect(harness.attempts.length, 2, reason: 'still waiting at <2s');
        async.elapse(const Duration(milliseconds: 2));
        async.flushMicrotasks();
        expect(harness.attempts.length, 3);
        harness.failNow(2);
        async.flushMicrotasks();

        // 4s later: 4th attempt.
        async.elapse(const Duration(seconds: 4) - const Duration(milliseconds: 1));
        async.flushMicrotasks();
        expect(harness.attempts.length, 3, reason: 'still waiting at <4s');
        async.elapse(const Duration(milliseconds: 2));
        async.flushMicrotasks();
        expect(harness.attempts.length, 4);

        stream.dispose();
      });
    });

    test('backoff resets to 1s after a successful connection', () {
      fakeAsync((async) {
        final harness = _Harness();
        final stream = EventStream(
          uri: Uri.parse('ws://test/api/events'),
          connector: harness.connect,
        );
        stream.start();
        async.flushMicrotasks();
        expect(harness.attempts.length, 1);

        // First attempt fails.
        harness.failNow(0);
        async.flushMicrotasks();

        // 2nd attempt at t=1s.
        async.elapse(const Duration(seconds: 1));
        async.flushMicrotasks();
        expect(harness.attempts.length, 2);
        // Make 2nd attempt also fail so backoff would normally grow to 2s.
        harness.failNow(1);
        async.flushMicrotasks();

        // 3rd attempt at t=3s (1s + 2s).
        async.elapse(const Duration(seconds: 2));
        async.flushMicrotasks();
        expect(harness.attempts.length, 3);

        // 3rd attempt SUCCEEDS — backoff should reset.
        harness.successThenClose(2);
        async.flushMicrotasks();

        // Now the successfully-connected socket drops.
        harness.closeStream(2);
        async.flushMicrotasks();

        // The next reconnect must use the reset 1s backoff (NOT 4s).
        async.elapse(const Duration(milliseconds: 999));
        async.flushMicrotasks();
        expect(harness.attempts.length, 3, reason: 'still waiting at <1s');
        async.elapse(const Duration(milliseconds: 2));
        async.flushMicrotasks();
        expect(harness.attempts.length, 4, reason: 'reconnect at t=1s after success');

        stream.dispose();
      });
    });

    test('backoff is capped at maxBackoff', () {
      fakeAsync((async) {
        final harness = _Harness();
        final stream = EventStream(
          uri: Uri.parse('ws://test/api/events'),
          connector: harness.connect,
          maxBackoff: const Duration(seconds: 30),
        );
        stream.start();
        async.flushMicrotasks();

        // Burn through 6 failures: delays 1s, 2s, 4s, 8s, 16s, 30s (cap).
        // Total elapsed when 7th attempt occurs: 1+2+4+8+16+30 = 61s.
        for (var i = 0; i < 6; i++) {
          harness.failNow(harness.attempts.length - 1);
          async.flushMicrotasks();
          // Use a generous slice so each scheduled reconnect fires.
          async.elapse(const Duration(seconds: 31));
          async.flushMicrotasks();
        }
        expect(harness.attempts.length, 7);

        // After yet another failure, the next attempt must come at t+30s,
        // not t+60s — the cap holds.
        harness.failNow(6);
        async.flushMicrotasks();
        async.elapse(const Duration(seconds: 29));
        async.flushMicrotasks();
        expect(harness.attempts.length, 7, reason: 'still capped, waiting at <30s');
        async.elapse(const Duration(seconds: 2));
        async.flushMicrotasks();
        expect(harness.attempts.length, 8);

        stream.dispose();
      });
    });

    test('on reconnect fires onReconnected callback (not on initial connect)',
        () {
      fakeAsync((async) {
        final harness = _Harness();
        var reconnectCount = 0;
        final stream = EventStream(
          uri: Uri.parse('ws://test/api/events'),
          connector: harness.connect,
          onReconnected: () => reconnectCount++,
        );
        stream.start();
        async.flushMicrotasks();

        // First connect succeeds — callback must NOT fire on initial.
        harness.successThenClose(0);
        async.flushMicrotasks();
        expect(reconnectCount, 0);

        // Drop and reconnect.
        harness.closeStream(0);
        async.flushMicrotasks();
        async.elapse(const Duration(seconds: 1));
        async.flushMicrotasks();

        expect(harness.attempts.length, 2);
        harness.successThenClose(1);
        async.flushMicrotasks();
        expect(reconnectCount, 1, reason: 'callback fires on RE-connect');

        stream.dispose();
      });
    });
  });

  group('EventStream message decoding', () {
    test('parses EventEnvelope from JSON text frames', () {
      fakeAsync((async) {
        final harness = _Harness();
        final stream = EventStream(
          uri: Uri.parse('ws://test/api/events'),
          connector: harness.connect,
        );
        final received = <EventEnvelope>[];
        final sub = stream.stream.listen(received.add);
        stream.start();
        async.flushMicrotasks();

        harness.successThenClose(0);
        async.flushMicrotasks();

        harness.streams[0].add(
          '{"type":"audio.state","payload":{"masterVolume":0.5,"muted":false},'
          '"timestamp":"2026-05-31T10:00:00Z"}',
        );
        async.flushMicrotasks();

        expect(received, hasLength(1));
        expect(received[0].type, 'audio.state');
        expect(received[0].payload['masterVolume'], 0.5);
        expect(received[0].payload['muted'], false);
        expect(received[0].timestamp, '2026-05-31T10:00:00Z');

        sub.cancel();
        stream.dispose();
      });
    });

    test('ignores malformed frames without crashing reconnect logic', () {
      fakeAsync((async) {
        final harness = _Harness();
        final stream = EventStream(
          uri: Uri.parse('ws://test/api/events'),
          connector: harness.connect,
        );
        final received = <EventEnvelope>[];
        final sub = stream.stream.listen(received.add);
        stream.start();
        async.flushMicrotasks();

        harness.successThenClose(0);
        async.flushMicrotasks();

        harness.streams[0].add('not json at all');
        harness.streams[0].add('{"unrelated":1}');
        harness.streams[0].add(
          '{"type":"file.created","payload":{"rootId":"docs","path":"a.txt"},'
          '"timestamp":"2026-05-31T10:00:01Z"}',
        );
        async.flushMicrotasks();

        // Only the well-formed envelope should surface.
        expect(received, hasLength(1));
        expect(received[0].type, 'file.created');

        sub.cancel();
        stream.dispose();
      });
    });
  });

  group('EventStream lifecycle', () {
    test('dispose cancels pending reconnect timer', () {
      fakeAsync((async) {
        final harness = _Harness();
        final stream = EventStream(
          uri: Uri.parse('ws://test/api/events'),
          connector: harness.connect,
        );
        stream.start();
        async.flushMicrotasks();

        harness.failNow(0);
        async.flushMicrotasks();

        // A reconnect is now scheduled in 1s. Dispose before it fires.
        stream.dispose();
        async.elapse(const Duration(seconds: 5));
        async.flushMicrotasks();

        // No further connect attempts after dispose.
        expect(harness.attempts.length, 1);
      });
    });
  });
}
