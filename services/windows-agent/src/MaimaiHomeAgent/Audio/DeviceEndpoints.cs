using Microsoft.AspNetCore.Http.HttpResults;

namespace MaimaiHomeAgent.Audio;

public static class DeviceEndpoints
{
    public static IEndpointRouteBuilder MapDeviceEndpoints(this IEndpointRouteBuilder app)
    {
        app.MapGet("/api/audio/devices", async Task<Results<Ok<IReadOnlyList<DeviceResponse>>, JsonHttpResult<ErrorResponse>>> (IAudioService audioService) =>
        {
            try
            {
                var devices = await audioService.ListDevicesAsync().ConfigureAwait(false);
                return TypedResults.Ok(Project(devices));
            }
            catch (OperationCanceledException ex)
            {
                return DeviceUnavailableResult(ex.Message);
            }
            catch (ObjectDisposedException ex)
            {
                return DeviceUnavailableResult(ex.Message);
            }
            catch (AudioOperationException ex)
            {
                return DeviceUnavailableResult(ex.Message);
            }
        });

        app.MapPost("/api/audio/default-device", async Task<Results<Ok<IReadOnlyList<DeviceResponse>>, NotFound<ErrorResponse>, BadRequest<ErrorResponse>, JsonHttpResult<ErrorResponse>>> (
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
            catch (OperationCanceledException ex)
            {
                return DeviceUnavailableResult(ex.Message);
            }
            catch (ObjectDisposedException ex)
            {
                return DeviceUnavailableResult(ex.Message);
            }
            catch (AudioOperationException ex)
            {
                return DeviceUnavailableResult(ex.Message);
            }

            try
            {
                var devices = await audioService.ListDevicesAsync().ConfigureAwait(false);
                return TypedResults.Ok(Project(devices));
            }
            catch (OperationCanceledException ex)
            {
                return DeviceUnavailableResult(ex.Message);
            }
            catch (ObjectDisposedException ex)
            {
                return DeviceUnavailableResult(ex.Message);
            }
            catch (AudioOperationException ex)
            {
                return DeviceUnavailableResult(ex.Message);
            }
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

    private static JsonHttpResult<ErrorResponse> DeviceUnavailableResult(string? detail)
        => TypedResults.Json(
            new ErrorResponse("device_unavailable", FormatDeviceUnavailableMessage(detail)),
            statusCode: StatusCodes.Status502BadGateway);

    private static string FormatDeviceUnavailableMessage(string? detail)
        => string.IsNullOrWhiteSpace(detail)
            ? "音频设备不可用"
            : $"音频设备不可用：{detail}";
}

public sealed record SetDefaultDeviceRequest(string DeviceId);
public sealed record DeviceResponse(string Id, string Name, bool IsDefault, string State);
public sealed record ErrorResponse(string Error, string? Message = null);
