using Microsoft.AspNetCore.Http.HttpResults;

namespace MaimaiHomeAgent.Audio;

public static class DeviceEndpoints
{
    public static IEndpointRouteBuilder MapDeviceEndpoints(this IEndpointRouteBuilder app)
    {
        app.MapGet("/api/audio/devices", async Task<Ok<IReadOnlyList<DeviceResponse>>> (IAudioService audioService) =>
        {
            var devices = await audioService.ListDevicesAsync().ConfigureAwait(false);
            return TypedResults.Ok(Project(devices));
        });

        app.MapPost("/api/audio/default-device", async Task<Results<Ok<IReadOnlyList<DeviceResponse>>, NotFound<ErrorResponse>, BadRequest<ErrorResponse>>> (
            SetDefaultDeviceRequest request,
            IAudioService audioService) =>
        {
            if (!Guid.TryParse(request.DeviceId, out var deviceId))
            {
                return TypedResults.BadRequest(new ErrorResponse("invalid_device_id"));
            }

            try
            {
                await audioService.SetDefaultDeviceAsync(deviceId).ConfigureAwait(false);
            }
            catch (AudioDeviceNotFoundException)
            {
                return TypedResults.NotFound(new ErrorResponse("device_not_found"));
            }

            var devices = await audioService.ListDevicesAsync().ConfigureAwait(false);
            return TypedResults.Ok(Project(devices));
        });

        return app;
    }

    internal static IReadOnlyList<DeviceResponse> Project(IReadOnlyList<AudioDevice> devices)
        => devices.Select(device => new DeviceResponse(
            device.Id.ToString(),
            device.Name,
            device.IsDefault,
            device.State.ToString().ToLowerInvariant()))
        .ToArray();
}

public sealed record SetDefaultDeviceRequest(string DeviceId);
public sealed record DeviceResponse(string Id, string Name, bool IsDefault, string State);
public sealed record ErrorResponse(string Error);
