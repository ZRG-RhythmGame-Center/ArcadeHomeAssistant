using System.Reflection;
using MaimaiHomeAgent.Audio;
using MaimaiHomeAgent.Files;
using MaimaiHomeAgent.Discovery;
using MaimaiHomeAgent.Realtime;
using MaimaiHomeAgent.Startup;
using MaimaiHomeAgent.Tray;
using Serilog;
using Serilog.Settings.Configuration;

Log.Logger = new LoggerConfiguration()
    .MinimumLevel.Information()
    .WriteTo.Console()
    .CreateBootstrapLogger();

try
{
    // Pin ContentRoot to the executable's directory so appsettings.json,
    // wwwroot, and other content-relative resources load regardless of the
    // working directory the user (or a tray launch) was started in.
    // AppContext.BaseDirectory is the right choice for single-file publish:
    // it points at the *extracted* runtime directory, which is also where the
    // Web SDK drops appsettings.json and the staticwebassets manifest.
    var builder = WebApplication.CreateBuilder(new WebApplicationOptions
    {
        Args = args,
        ContentRootPath = AppContext.BaseDirectory,
    });

    var logPath = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
        "maimai-home-assistant", "logs", "agent-.log");
    builder.Configuration["Serilog:WriteTo:1:Args:path"] = logPath;

    builder.Host.UseSerilog((context, services, configuration) =>
    {
        // Serilog's default AssemblyFinder cannot scan files in single-file publish.
        // Pass the sink assemblies explicitly so ReadFrom.Configuration resolves them.
        var serilogOptions = new ConfigurationReaderOptions(
            typeof(Serilog.ConsoleLoggerConfigurationExtensions).Assembly,
            typeof(Serilog.FileLoggerConfigurationExtensions).Assembly);

        configuration
            .ReadFrom.Configuration(context.Configuration, serilogOptions)
            .ReadFrom.Services(services)
            .Enrich.FromLogContext();
    });

    builder.Services.AddOpenApi();
    builder.Services.Configure<DiscoveryOptions>(builder.Configuration.GetSection("Discovery"));
    builder.Services.AddHostedService<MdnsAdvertiser>();

    builder.Services.AddSingleton<AudioStaDispatcher>();
    builder.Services.AddHostedService(sp => sp.GetRequiredService<AudioStaDispatcher>());
    builder.Services.AddSingleton<IAudioService, CoreAudioService>();
    builder.Services.AddSingleton<IAudioDeviceNotificationSource, NAudioDeviceNotificationSource>();
    builder.Services.AddHostedService<DeviceChangeNotifier>();

    builder.Services.AddSingleton<IFileRootService, FileRootService>();

    builder.Services.Configure<HeartbeatOptions>(builder.Configuration.GetSection("Realtime:Heartbeat"));
    builder.Services.AddSingleton<EventHub>();
    builder.Services.AddSingleton<EventPublisher>();
    builder.Services.AddHostedService<HeartbeatService>();

    if (OperatingSystem.IsWindows())
    {
        builder.Services.AddSingleton<IProcessRunner, ProcessRunner>();
        builder.Services.AddSingleton<AutoStartManager>();
        builder.Services.AddHostedService<TrayApp>();
    }

    var app = builder.Build();

    if (app.Environment.IsDevelopment())
    {
        app.MapOpenApi();
    }

    app.UseSerilogRequestLogging();
    app.UseDefaultFiles();
    app.UseStaticFiles();
    app.UseWebSockets();

    var startedAt = DateTimeOffset.UtcNow;
    var version = Assembly.GetExecutingAssembly().GetName().Version?.ToString() ?? "0.0.0";

    app.MapGet("/api/status", (HttpContext ctx) =>
    {
        var uptime = DateTimeOffset.UtcNow - startedAt;
        // baseUrl: derived from the inbound request so the mobile client can
        // round-trip the canonical address it should use for subsequent calls.
        // Closes Gate F #3 / R2 I20 / mobile-side AgentStatus.baseUrl field.
        var baseUrl = $"{ctx.Request.Scheme}://{ctx.Request.Host}";
        return Results.Ok(new
        {
            machineName = Environment.MachineName,
            version,
            startedAt,
            uptimeSeconds = (long)uptime.TotalSeconds,
            baseUrl,
            capabilities = new
            {
                audioVolume = true,
                audioMute = true,
                audioDeviceSwitch = true,
                fileManagement = true,
                discoveryBroadcast = true
            }
        });
    });

    app.Map("/api/events", async (HttpContext ctx, EventHub hub, CancellationToken ct) =>
    {
        if (!ctx.WebSockets.IsWebSocketRequest)
        {
            ctx.Response.StatusCode = StatusCodes.Status400BadRequest;
            return;
        }

        // Optional ?token= query is preserved as a no-op client identifier
        // for backwards compatibility (e.g. mobile app builds that still set it).

        using var socket = await ctx.WebSockets.AcceptWebSocketAsync();
        var token = ctx.Request.Query["token"].FirstOrDefault();
        await hub.AddAsync(socket, token, ct);
    });

    app.MapFileRootsConfigEndpoints();
    app.MapFileListingEndpoints();
    app.MapFileMutationEndpoints();
    app.MapAudioEndpoints();
    app.MapDeviceEndpoints();


    // Fallback to the SPA shell for any non-API route. Registered AFTER all
    // /api endpoints so it never swallows API requests; static assets under
    // wwwroot are still served first by UseStaticFiles above.
    app.MapFallbackToFile("index.html");

    Log.Information("Maimai Home Agent starting. Machine={Machine} Version={Version}",
        Environment.MachineName, version);

    app.Run();
}
catch (Exception ex) when (ex is not HostAbortedException)
{
    Log.Fatal(ex, "Maimai Home Agent terminated unexpectedly");
}
finally
{
    Log.CloseAndFlush();
}

/// <summary>
/// Exposed for <see cref="Microsoft.AspNetCore.Mvc.Testing.WebApplicationFactory{TEntryPoint}"/>.
/// Top-level statements generate an internal Program class by default; the test
/// host needs a public surface to bind to.
/// </summary>
public partial class Program;
