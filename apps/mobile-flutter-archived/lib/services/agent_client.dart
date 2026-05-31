import 'dart:io';
import 'package:dio/dio.dart';

/// Categorical error mapping for Agent client failures.
///
/// Used by [AgentClientException] to keep UI/state layers decoupled from the
/// concrete HTTP client. Add new variants only when a UI flow needs to react
/// differently; otherwise prefer [AgentException.unknown].
enum AgentException {
  /// Network is unreachable, DNS failed, or request timed out.
  network,

  /// Server responded with HTTP 401 — unauthorized.
  unauthorized,

  /// Server responded with HTTP 503 — audio dispatcher queue is full.
  busy,

  /// Server responded with HTTP 502 — the targeted audio device is
  /// unavailable (unplugged / disabled / Core Audio failure).
  deviceUnavailable,

  /// Anything else: unexpected status code, malformed payload, etc.
  unknown,
}

/// Exception thrown by [AgentClient] when a request fails.
class AgentClientException implements Exception {
  const AgentClientException(this.kind, [this.message]);

  final AgentException kind;
  final String? message;

  @override
  String toString() => 'AgentClientException(${kind.name}: ${message ?? ''})';
}

/// Capabilities advertised by `/api/status`.
class AgentStatus {
  const AgentStatus({
    required this.baseUrl,
    required this.machineName,
    required this.version,
    required this.uptimeSeconds,
    required this.capabilities,
  });

  final String baseUrl;
  final String machineName;
  final String version;
  final int uptimeSeconds;

  /// Raw capability flags. Currently published keys:
  /// `audioVolume`, `audioMute`, `audioDeviceSwitch`, `fileManagement`,
  /// `discoveryBroadcast`. Unknown keys are preserved as-is for forward compat.
  final Map<String, bool> capabilities;

  bool get audioVolume => capabilities['audioVolume'] ?? false;
  bool get audioMute => capabilities['audioMute'] ?? false;
  bool get audioDeviceSwitch => capabilities['audioDeviceSwitch'] ?? false;
  bool get fileManagement => capabilities['fileManagement'] ?? false;
  bool get discoveryBroadcast => capabilities['discoveryBroadcast'] ?? false;

  factory AgentStatus.fromJson(Map<String, dynamic> json, String baseUrl) {
    final raw = json['capabilities'];
    final caps = <String, bool>{};
    if (raw is Map) {
      raw.forEach((key, value) {
        if (key is String) {
          caps[key] = value == true;
        }
      });
    }

    return AgentStatus(
      baseUrl: baseUrl,
      machineName: json['machineName']?.toString() ?? 'unknown',
      version: json['version']?.toString() ?? 'unknown',
      uptimeSeconds: (json['uptimeSeconds'] as num?)?.toInt() ?? 0,
      capabilities: caps,
    );
  }
}

/// Thin Dio wrapper for the Windows Agent HTTP API.
///
/// Construction takes the raw user-entered address; the client normalizes it
/// internally so callers pass the literal string from the input field.
class AgentClient {
  AgentClient({
    required String baseUrl,
    Dio? dio,
  })  : _rawBaseUrl = baseUrl,
        _dio = dio ?? Dio();

  final String _rawBaseUrl;
  final Dio _dio;

  /// Returns the normalized base URL or null if unparseable.
  String? get baseUrl => normalizeBaseUrl(_rawBaseUrl);

  /// Build the [Options] used for every request. Centralized so the timeout
  /// settings stay in lockstep across endpoints.
  Options _authedOptions({
    Duration sendTimeout = const Duration(seconds: 5),
    Duration receiveTimeout = const Duration(seconds: 5),
    String? contentType,
    String? method,
    Map<String, dynamic>? extraHeaders,
  }) {
    final headers = <String, dynamic>{};
    if (extraHeaders != null) {
      headers.addAll(extraHeaders);
    }
    return Options(
      sendTimeout: sendTimeout,
      receiveTimeout: receiveTimeout,
      headers: headers,
      contentType: contentType,
      method: method,
    );
  }

  Future<AgentStatus> fetchStatus() async {
    final normalized = normalizeBaseUrl(_rawBaseUrl);
    if (normalized == null) {
      throw const AgentClientException(
        AgentException.unknown,
        '请输入有效的 Agent 地址，例如 192.168.1.100:8765',
      );
    }

    try {
      final response = await _dio.get<Map<String, dynamic>>(
        '$normalized/api/status',
        options: _authedOptions(),
      );

      final data = response.data;
      if (data == null) {
        throw const AgentClientException(
          AgentException.unknown,
          '响应为空',
        );
      }

      return AgentStatus.fromJson(data, normalized);
    } on AgentClientException {
      rethrow;
    } on DioException catch (error) {
      throw _toException(error);
    } on FormatException catch (error) {
      throw AgentClientException(
        AgentException.unknown,
        '返回内容格式不正确：${error.message}',
      );
    } catch (error) {
      throw AgentClientException(AgentException.unknown, error.toString());
    }
  }

  void close() {
    _dio.close();
  }



  static AgentException _mapDioType(DioException error) {
    switch (error.type) {
      case DioExceptionType.connectionTimeout:
      case DioExceptionType.sendTimeout:
      case DioExceptionType.receiveTimeout:
      case DioExceptionType.connectionError:
        return AgentException.network;
      case DioExceptionType.badResponse:
        switch (error.response?.statusCode) {
          case 401:
            return AgentException.unauthorized;
          case 502:
            return AgentException.deviceUnavailable;
          case 503:
            return AgentException.busy;
          default:
            return AgentException.unknown;
        }
      case DioExceptionType.cancel:
      case DioExceptionType.badCertificate:
      case DioExceptionType.unknown:
        return AgentException.unknown;
    }
  }

  /// Convert a DioException to an [AgentClientException]. Centralized so
  /// every endpoint method gets identical mapping.
  AgentClientException _toException(DioException error) {
    return AgentClientException(_mapDioType(error), error.message);
  }

  /// Normalize the raw user input into a canonical `scheme://host[:port]`.
  ///
  /// - empty / whitespace -> null
  /// - no scheme -> prepend `http://`
  /// - unparseable host -> null
  ///
  /// Public so the connection page can pre-validate before dispatching.
  static String? normalizeBaseUrl(String input) {
    final trimmed = input.trim();
    if (trimmed.isEmpty) {
      return null;
    }

    final withScheme =
        trimmed.startsWith('http://') || trimmed.startsWith('https://')
            ? trimmed
            : 'http://$trimmed';
    final uri = Uri.tryParse(withScheme);

    if (uri == null || uri.host.isEmpty) {
      return null;
    }

    return uri.hasPort
        ? '${uri.scheme}://${uri.host}:${uri.port}'
        : '${uri.scheme}://${uri.host}';
  }

  /// Maps a DioException to a user-facing Chinese message. Kept here so the
  /// connection page does not have to know about Dio internals.
  static String describeError(AgentClientException error) {
    switch (error.kind) {
      case AgentException.network:
        return '无法连接到 Agent，请确认地址、端口和防火墙设置。';
      case AgentException.unauthorized:
        return 'Agent 返回 HTTP 401（未授权）。';
      case AgentException.busy:
        return '服务忙，请稍后重试';
      case AgentException.deviceUnavailable:
        return '设备不可用';
      case AgentException.unknown:
        return error.message?.isNotEmpty == true
            ? '请求失败：${error.message}'
            : '请求失败。';
    }
  }

  // ---- Audio API ----

  /// GET /api/audio/state — current master volume / mute / default device id.
  Future<AudioState> fetchAudioState() async {
    return _audioRequest(
      () => _dio.get<Map<String, dynamic>>(
        '${_requireBase()}/api/audio/state',
        options: _authedOptions(),
      ),
      AudioState.fromJson,
    );
  }

  /// GET /api/audio/devices — render endpoint list.
  Future<List<AudioDevice>> fetchAudioDevices() async {
    final normalized = _requireBase();
    try {
      final response = await _dio.get<List<dynamic>>(
        '$normalized/api/audio/devices',
        options: _authedOptions(),
      );
      final data = response.data ?? const [];
      return data
          .whereType<Map<String, dynamic>>()
          .map(AudioDevice.fromJson)
          .toList(growable: false);
    } on AgentClientException {
      rethrow;
    } on DioException catch (error) {
      throw _toException(error);
    } catch (error) {
      throw AgentClientException(AgentException.unknown, error.toString());
    }
  }

  /// POST /api/audio/volume — body `{ level: 0..1 }`. Returns the post-state.
  Future<AudioState> setVolume(double level) async {
    return _audioRequest(
      () => _dio.post<Map<String, dynamic>>(
        '${_requireBase()}/api/audio/volume',
        data: {'level': level},
        options: _authedOptions(),
      ),
      AudioState.fromJson,
    );
  }

  /// POST /api/audio/mute — body `{ muted: bool }`. Returns the post-state.
  Future<AudioState> setMute(bool muted) async {
    return _audioRequest(
      () => _dio.post<Map<String, dynamic>>(
        '${_requireBase()}/api/audio/mute',
        data: {'muted': muted},
        options: _authedOptions(),
      ),
      AudioState.fromJson,
    );
  }

  /// POST /api/audio/default-device — body `{ deviceId: string }`. Server
  /// returns 200 + payload, but the response shape is an [AudioDevice]; we
  /// discard it because callers refetch the device list anyway.
  Future<void> switchDevice(String deviceId) async {
    final normalized = _requireBase();
    try {
      await _dio.post<Map<String, dynamic>>(
        '$normalized/api/audio/default-device',
        data: {'deviceId': deviceId},
        options: _authedOptions(),
      );
    } on AgentClientException {
      rethrow;
    } on DioException catch (error) {
      throw _toException(error);
    } catch (error) {
      throw AgentClientException(AgentException.unknown, error.toString());
    }
  }

  // ---- File management API ----

  /// `GET /api/file-roots` — list configured roots, sanitized for clients.
  Future<List<FileRoot>> fetchFileRoots() async {
    final base = _requireBase();
    try {
      final response = await _dio.get<List<dynamic>>(
        '$base/api/file-roots',
        options: _authedOptions(),
      );
      final data = response.data ?? const [];
      return data
          .whereType<Map>()
          .map((raw) => FileRoot.fromJson(raw.cast<String, dynamic>()))
          .toList(growable: false);
    } on AgentClientException {
      rethrow;
    } on DioException catch (error) {
      throw _toException(error);
    } catch (error) {
      throw AgentClientException(AgentException.unknown, error.toString());
    }
  }

  /// `GET /api/files?rootId=&path=&limit=` — single-level directory listing.
  Future<FileListingResult> fetchFiles(
    String rootId,
    String path, {
    int limit = 200,
  }) async {
    return _audioRequest(
      () => _dio.get<Map<String, dynamic>>(
        '${_requireBase()}/api/files',
        queryParameters: {
          'rootId': rootId,
          'path': path,
          'limit': limit,
        },
        options: _authedOptions(
          receiveTimeout: const Duration(seconds: 10),
        ),
      ),
      FileListingResult.fromJson,
    );
  }

  /// `POST /api/files/upload` — multipart upload of a single local [file].
  ///
  /// `path` is the destination *relative to the root* — e.g.
  /// `'incoming/report.pdf'`. Set [overwrite] to allow replacing an existing
  /// file (otherwise the agent returns 409 `file_exists`).
  Future<void> uploadFile(
    String rootId,
    String path,
    File file, {
    bool overwrite = false,
  }) async {
    final base = _requireBase();
    try {
      final form = FormData.fromMap({
        'rootId': rootId,
        'path': path,
        'overwrite': overwrite ? 'true' : 'false',
        'file': await MultipartFile.fromFile(file.path),
      });
      await _dio.post<dynamic>(
        '$base/api/files/upload',
        data: form,
        options: _authedOptions(
          sendTimeout: const Duration(minutes: 5),
          receiveTimeout: const Duration(minutes: 5),
          extraHeaders: {Headers.contentTypeHeader: 'multipart/form-data'},
        ),
      );
    } on AgentClientException {
      rethrow;
    } on DioException catch (error) {
      throw _toException(error);
    } catch (error) {
      throw AgentClientException(AgentException.unknown, error.toString());
    }
  }

  /// `GET /api/files/download` — stream the file body to [savePath] on disk.
  Future<void> downloadFile(
    String rootId,
    String path,
    String savePath,
  ) async {
    final base = _requireBase();
    try {
      await _dio.download(
        '$base/api/files/download',
        savePath,
        queryParameters: {'rootId': rootId, 'path': path},
        options: _authedOptions(
          sendTimeout: const Duration(seconds: 30),
          receiveTimeout: const Duration(minutes: 5),
        ),
      );
    } on AgentClientException {
      rethrow;
    } on DioException catch (error) {
      throw _toException(error);
    } catch (error) {
      throw AgentClientException(AgentException.unknown, error.toString());
    }
  }

  /// `DELETE /api/files` — `confirm:true` is auto-injected by the client.
  Future<void> deleteFile(String rootId, String path) async {
    return _mutateFile(
      method: 'DELETE',
      url: '${_requireBase()}/api/files',
      data: {'rootId': rootId, 'path': path, 'confirm': true},
    );
  }

  /// `POST /api/files/rename` — `confirm:true` is auto-injected.
  Future<void> renameFile(
    String rootId,
    String path,
    String newName,
  ) async {
    return _mutateFile(
      method: 'POST',
      url: '${_requireBase()}/api/files/rename',
      data: {
        'rootId': rootId,
        'path': path,
        'newName': newName,
        'confirm': true,
      },
    );
  }

  /// `POST /api/files/move` — `confirm:true` is auto-injected.
  ///
  /// Cross-root moves are NOT supported: callers pass the same [rootId] for
  /// both source and destination paths.
  Future<void> moveFile(
    String rootId,
    String fromPath,
    String toPath,
  ) async {
    return _mutateFile(
      method: 'POST',
      url: '${_requireBase()}/api/files/move',
      data: {
        'rootId': rootId,
        'fromPath': fromPath,
        'toPath': toPath,
        'confirm': true,
      },
    );
  }

  Future<void> _mutateFile({
    required String method,
    required String url,
    required Map<String, dynamic> data,
  }) async {
    try {
      await _dio.request<dynamic>(
        url,
        data: data,
        options: _authedOptions(
          method: method,
          contentType: Headers.jsonContentType,
          receiveTimeout: const Duration(seconds: 10),
        ),
      );
    } on AgentClientException {
      rethrow;
    } on DioException catch (error) {
      throw _toException(error);
    } catch (error) {
      throw AgentClientException(AgentException.unknown, error.toString());
    }
  }

  /// Common shape for state-returning audio endpoints: parse map data and map
  /// the usual error categories. Extracted to keep the four methods small.
  Future<T> _audioRequest<T>(
    Future<Response<Map<String, dynamic>>> Function() send,
    T Function(Map<String, dynamic>) parse,
  ) async {
    try {
      final response = await send();
      final data = response.data;
      if (data == null) {
        throw const AgentClientException(
          AgentException.unknown,
          '响应为空',
        );
      }
      return parse(data);
    } on AgentClientException {
      rethrow;
    } on DioException catch (error) {
      throw _toException(error);
    } on FormatException catch (error) {
      throw AgentClientException(
        AgentException.unknown,
        '返回内容格式不正确：${error.message}',
      );
    } catch (error) {
      throw AgentClientException(AgentException.unknown, error.toString());
    }
  }

  String _requireBase() {
    final normalized = normalizeBaseUrl(_rawBaseUrl);
    if (normalized == null) {
      throw const AgentClientException(
        AgentException.unknown,
        '请输入有效的 Agent 地址，例如 192.168.1.100:8765',
      );
    }
    return normalized;
  }
}

/// Master mixer state returned by `/api/audio/state` and the `audio.state`
/// realtime broadcast.
class AudioState {
  const AudioState({
    required this.masterVolume,
    required this.muted,
    required this.defaultDeviceId,
  });

  /// 0.0 – 1.0 range; agent normalizes any out-of-range values server-side.
  final double masterVolume;
  final bool muted;
  final String? defaultDeviceId;

  factory AudioState.fromJson(Map<String, dynamic> json) {
    final raw = json['masterVolume'];
    final volume = raw is num ? raw.toDouble() : 0.0;
    final muted = json['muted'] == true;
    final id = json['defaultDeviceId'];
    return AudioState(
      masterVolume: volume.clamp(0.0, 1.0).toDouble(),
      muted: muted,
      defaultDeviceId: id?.toString(),
    );
  }

  AudioState copyWith({double? masterVolume, bool? muted, String? defaultDeviceId}) {
    return AudioState(
      masterVolume: masterVolume ?? this.masterVolume,
      muted: muted ?? this.muted,
      defaultDeviceId: defaultDeviceId ?? this.defaultDeviceId,
    );
  }

  @override
  bool operator ==(Object other) =>
      other is AudioState &&
      other.masterVolume == masterVolume &&
      other.muted == muted &&
      other.defaultDeviceId == defaultDeviceId;

  @override
  int get hashCode => Object.hash(masterVolume, muted, defaultDeviceId);
}

/// Render endpoint as exposed by `/api/audio/devices`.
class AudioDevice {
  const AudioDevice({
    required this.id,
    required this.name,
    required this.isDefault,
    required this.state,
  });

  final String id;
  final String name;
  final bool isDefault;

  /// Lower-case state string from the server (`active`, `disabled`,
  /// `notpresent`, `unplugged`). Kept as String to stay forward-compatible.
  final String state;

  factory AudioDevice.fromJson(Map<String, dynamic> json) {
    return AudioDevice(
      id: json['id']?.toString() ?? '',
      name: json['name']?.toString() ?? '',
      isDefault: json['isDefault'] == true,
      state: json['state']?.toString() ?? '',
    );
  }

  @override
  bool operator ==(Object other) =>
      other is AudioDevice &&
      other.id == id &&
      other.name == name &&
      other.isDefault == isDefault &&
      other.state == state;

  @override
  int get hashCode => Object.hash(id, name, isDefault, state);
}

/// Configured file root advertised by `GET /api/file-roots`.
///
/// The agent intentionally never exposes the on-disk path to clients — only
/// the stable [id], the human [name], and a [readOnly] flag.
class FileRoot {
  const FileRoot({
    required this.id,
    required this.name,
    required this.readOnly,
  });

  final String id;
  final String name;
  final bool readOnly;

  factory FileRoot.fromJson(Map<String, dynamic> json) => FileRoot(
        id: json['id']?.toString() ?? '',
        name: json['name']?.toString() ?? '',
        readOnly: json['readOnly'] == true,
      );

  @override
  bool operator ==(Object other) =>
      other is FileRoot &&
      other.id == id &&
      other.name == name &&
      other.readOnly == readOnly;

  @override
  int get hashCode => Object.hash(id, name, readOnly);
}

/// One directory entry returned by `GET /api/files`.
class FileEntry {
  const FileEntry({
    required this.name,
    required this.kind,
    required this.size,
    required this.modified,
  });

  /// File or directory name (no leading path).
  final String name;

  /// `"file"` or `"dir"` — string rather than enum so unknown values
  /// returned by future agent versions don't blow up parsing.
  final String kind;

  /// File size in bytes; `null` for directories.
  final int? size;

  /// Last-write time in UTC (per agent contract).
  final DateTime modified;

  bool get isDirectory => kind == 'dir';

  factory FileEntry.fromJson(Map<String, dynamic> json) => FileEntry(
        name: json['name']?.toString() ?? '',
        kind: json['kind']?.toString() ?? 'file',
        size: (json['size'] as num?)?.toInt(),
        modified: DateTime.tryParse(json['modified']?.toString() ?? '') ??
            DateTime.fromMillisecondsSinceEpoch(0, isUtc: true),
      );
}

/// Paginated directory-listing response.
class FileListingResult {
  const FileListingResult({
    required this.entries,
    required this.total,
    required this.truncated,
  });

  final List<FileEntry> entries;
  final int total;
  final bool truncated;

  factory FileListingResult.fromJson(Map<String, dynamic> json) {
    final raw = json['entries'];
    final entries = raw is List
        ? raw
            .whereType<Map>()
            .map((e) => FileEntry.fromJson(e.cast<String, dynamic>()))
            .toList(growable: false)
        : const <FileEntry>[];
    return FileListingResult(
      entries: entries,
      total: (json['total'] as num?)?.toInt() ?? entries.length,
      truncated: json['truncated'] == true,
    );
  }
}
