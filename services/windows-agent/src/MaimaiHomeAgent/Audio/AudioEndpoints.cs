using MaimaiHomeAgent.Realtime;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Mvc;

namespace MaimaiHomeAgent.Audio;

/// <summary>
/// HTTP endpoints exposing default-playback-device audio control:
/// <list type="bullet">
///   <item><c>GET  /api/audio/state</c> — read current master volume / mute / device id.</item>
///   <item><c>POST /api/audio/volume</c> — set master volume on <c>[0.0, 1.0]</c>.</item>
///   <item><c>POST /api/audio/mute</c> — toggle the mute flag.</item>
/// </list>
/// </summary>
/// <remarks>
/// Every mutating call goes through <see cref="IAudioService"/>, which funnels
/// the underlying COM access onto a single STA thread via
/// <see cref="AudioStaDispatcher"/>; endpoint handlers MUST NOT touch Core Audio
/// directly. After a successful set we fetch the fresh state and broadcast an
/// <see cref="EventTypes.AudioState"/> envelope over the EventHub so connected
/// WebSocket clients reconcile without polling. Failures map to:
/// <list type="bullet">
///   <item><see cref="AudioServiceBusyException"/> — HTTP 503 with <c>Retry-After: 1</c>.</item>
///   <item><see cref="AudioOperationException"/> — HTTP 502 <c>device_unavailable</c>.</item>
/// </list>
/// </remarks>
public static class AudioEndpoints
{
    public static IEndpointRouteBuilder MapAudioEndpoints(this IEndpointRouteBuilder app)
    {
        ArgumentNullException.ThrowIfNull(app);

        app.MapGet("/api/audio/state", async (IAudioService audio) =>
        {
            try
            {
                var state = await audio.GetStateAsync().ConfigureAwait(false);
                return AudioStateOk(state);
            }
            catch (AudioServiceBusyException)
            {
                return BusyResult();
            }
            catch (AudioOperationException)
            {
                return DeviceUnavailableResult();
            }
        });

        app.MapPost("/api/audio/volume", async (
            VolumeRequest? body,
            IAudioService audio,
            EventPublisher events) =>
        {
            // Distinguish "missing JSON body" from "valid body". Minimal API binds
            // an empty/missing JSON to a default-constructed record where Level is
            // null; we treat that as a validation error rather than passing 0
            // through silently.
            if (body is null || body.Level is null)
            {
                return ValidationError("level must be between 0 and 1");
            }

            var level = body.Level.Value;
            if (double.IsNaN(level) || level < 0d || level > 1d)
            {
                return ValidationError("level must be between 0 and 1");
            }

            try
            {
                await audio.SetVolumeAsync(level).ConfigureAwait(false);
                var state = await audio.GetStateAsync().ConfigureAwait(false);
                var dto = new AudioStateDto(state.MasterVolume, state.Muted, state.DefaultDeviceId);
                events.PublishAudioStateChanged(dto);
                return Results.Ok(dto);
            }
            catch (AudioServiceBusyException)
            {
                return BusyResult();
            }
            catch (AudioOperationException)
            {
                return DeviceUnavailableResult();
            }
        });

        app.MapPost("/api/audio/mute", async (
            MuteRequest? body,
            IAudioService audio,
            EventPublisher events) =>
        {
            if (body is null || body.Muted is null)
            {
                return ValidationError("muted must be true or false");
            }

            try
            {
                await audio.SetMuteAsync(body.Muted.Value).ConfigureAwait(false);
                var state = await audio.GetStateAsync().ConfigureAwait(false);
                var dto = new AudioStateDto(state.MasterVolume, state.Muted, state.DefaultDeviceId);
                events.PublishAudioStateChanged(dto);
                return Results.Ok(dto);
            }
            catch (AudioServiceBusyException)
            {
                return BusyResult();
            }
            catch (AudioOperationException)
            {
                return DeviceUnavailableResult();
            }
        });

        return app;
    }

    private static IResult AudioStateOk(AudioState state)
        => Results.Ok(new AudioStateDto(state.MasterVolume, state.Muted, state.DefaultDeviceId));

    private static IResult ValidationError(string message)
        => Results.Json(
            new { error = "validation_error", message },
            statusCode: StatusCodes.Status400BadRequest);

    private static IResult DeviceUnavailableResult()
        => Results.Json(
            new { error = "device_unavailable" },
            statusCode: StatusCodes.Status502BadGateway);

    /// <summary>
    /// 503 Service Unavailable with <c>Retry-After: 1</c>. The header tells
    /// well-behaved clients to back off for a second; the body still carries the
    /// snake_case error code for programmatic handling.
    /// </summary>
    private static IResult BusyResult() => new BusyResultImpl();


    /// <summary>
    /// Custom <see cref="IResult"/> that writes a JSON body AND the
    /// <c>Retry-After</c> header. <see cref="Results.Json(object,System.Text.Json.JsonSerializerOptions?,string?,int?)"/>
    /// alone does not let us add response headers, hence the small adapter.
    /// </summary>
    private sealed class BusyResultImpl : IResult
    {
        public async Task ExecuteAsync(HttpContext httpContext)
        {
            httpContext.Response.StatusCode = StatusCodes.Status503ServiceUnavailable;
            httpContext.Response.Headers["Retry-After"] = "1";
            httpContext.Response.ContentType = "application/json; charset=utf-8";
            await httpContext.Response.WriteAsJsonAsync(new
            {
                error = "service_busy",
                message = "Audio dispatcher is busy. Retry shortly."
            }).ConfigureAwait(false);
        }
    }
}

/// <summary>
/// Wire DTO for <c>GET /api/audio/state</c> and the broadcast payload. Field
/// names use lower-camel via the JSON web defaults so mobile / web clients can
/// parse the envelope without custom converters.
/// </summary>
public sealed record AudioStateDto(double MasterVolume, bool Muted, Guid? DefaultDeviceId);

/// <summary>Body of <c>POST /api/audio/volume</c>. <c>Level</c> is nullable so
/// missing fields surface as 400 rather than defaulting to 0.</summary>
public sealed record VolumeRequest([property: System.Text.Json.Serialization.JsonPropertyName("level")] double? Level);

/// <summary>Body of <c>POST /api/audio/mute</c>. <c>Muted</c> is nullable for
/// the same reason as <see cref="VolumeRequest.Level"/>.</summary>
public sealed record MuteRequest([property: System.Text.Json.Serialization.JsonPropertyName("muted")] bool? Muted);
