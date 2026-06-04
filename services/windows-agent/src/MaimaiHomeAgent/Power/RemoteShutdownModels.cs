namespace MaimaiHomeAgent.Power;

public sealed record RemoteShutdownStatusDto(
    bool Available,
    string State,
    string? Error);

public sealed record RemoteShutdownEventDto(
    string State,
    DateTimeOffset ExecutedAt,
    string? Error);

public sealed record ExecuteShutdownRequest(bool? Confirm);

public sealed record ExecuteShutdownResult(
    bool Accepted,
    bool Conflict,
    RemoteShutdownStatusDto Status,
    string? Error = null);
