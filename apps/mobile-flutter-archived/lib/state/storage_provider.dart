import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:shared_preferences/shared_preferences.dart';

const String savedAgentAddressKey = 'saved_agent_address';

/// Provides a [SharedPreferences] instance. Override in tests via
/// `SharedPreferences.setMockInitialValues({})` (which the package supports
/// without requiring this override).
final sharedPreferencesProvider = FutureProvider<SharedPreferences>(
  (ref) => SharedPreferences.getInstance(),
);

/// Read/write the persisted agent address.
///
/// Async because shared_preferences is async. The connection page can either
/// `await` it on init or watch the `AsyncValue` form via `ref.watch`.
class SavedAddressController {
  const SavedAddressController(this._prefs);

  final SharedPreferences _prefs;

  String? read() => _prefs.getString(savedAgentAddressKey);

  Future<void> write(String address) =>
      _prefs.setString(savedAgentAddressKey, address);

  Future<void> clear() => _prefs.remove(savedAgentAddressKey);
}

final savedAddressControllerProvider = FutureProvider<SavedAddressController>(
  (ref) async {
    final prefs = await ref.watch(sharedPreferencesProvider.future);
    return SavedAddressController(prefs);
  },
);

/// Reads (and exposes as `AsyncValue<String?>`) the persisted address.
///
/// `null` -> no saved value yet, caller should fall back to the default.
final savedAddressProvider = FutureProvider<String?>((ref) async {
  final controller = await ref.watch(savedAddressControllerProvider.future);
  return controller.read();
});
