namespace MaimaiHomeAgent.Realtime;

/// <summary>
/// Wire payload for file-system change events broadcast over the /api/events
/// WebSocket. <see cref="NewPath"/> is populated only for rename/move events;
/// for create/delete it stays null and is omitted on the wire by the JSON web
/// defaults (we leave that policy to the serializer / consumer).
/// </summary>
/// <param name="RootId">Identifier of the configured file root the change originates from.</param>
/// <param name="Path">The path that changed, relative to the root.</param>
/// <param name="NewPath">For rename/move, the new path; null otherwise.</param>
public sealed record FileEventDto(string RootId, string Path, string? NewPath);
