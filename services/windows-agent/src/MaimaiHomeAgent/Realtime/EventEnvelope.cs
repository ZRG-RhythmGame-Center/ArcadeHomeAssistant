using System.Text.Json;
using System.Text.Json.Serialization;

namespace MaimaiHomeAgent.Realtime;

/// <summary>
///     Wire envelope sent over the /api/events WebSocket. Field names use lower-camel
///     when serialized so mobile/web clients can parse the payload directly.
/// </summary>
public sealed record EventEnvelope(
    [property: JsonPropertyName("type")] string Type,
    [property: JsonPropertyName("payload")]
    JsonElement Payload,
    [property: JsonPropertyName("timestamp")]
    DateTimeOffset Timestamp);