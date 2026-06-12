namespace MaimaiHomeAgent.Settings;

public interface IAgentSettingsService
{
    Task<AgentSettingsSnapshot> GetAsync(CancellationToken ct = default);

    Task<SettingsUpdateResult> UpdateAsync(AgentSettingsUpdateRequest request, CancellationToken ct = default);
}

public sealed record SettingsUpdateResult(
    bool Success,
    AgentSettingsSnapshot? Settings,
    IReadOnlyList<SettingsValidationError> Errors)
{
    public static SettingsUpdateResult Ok(AgentSettingsSnapshot settings)
    {
        return new SettingsUpdateResult(true, settings, Array.Empty<SettingsValidationError>());
    }

    public static SettingsUpdateResult Failed(IReadOnlyList<SettingsValidationError> errors)
    {
        return new SettingsUpdateResult(false, null, errors);
    }
}

public sealed record SettingsValidationError(string Error, string Message);