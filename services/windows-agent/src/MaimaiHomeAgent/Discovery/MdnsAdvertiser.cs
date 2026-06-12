using System.Net.NetworkInformation;
using Makaretu.Dns;
using Microsoft.Extensions.Options;

namespace MaimaiHomeAgent.Discovery;

public sealed class MdnsAdvertiser(
    IOptions<DiscoveryOptions> options,
    ILogger<MdnsAdvertiser> logger,
    IHostApplicationLifetime appLifetime) : IHostedService
{
    private readonly IHostApplicationLifetime _appLifetime = appLifetime;
    private readonly ILogger<MdnsAdvertiser> _logger = logger;
    private readonly DiscoveryOptions _options = options.Value;
    private readonly SemaphoreSlim _restartSemaphore = new(1, 1);

    private CancellationTokenSource? _restartCts;

    private ServiceDiscovery? _serviceDiscovery;
    private ServiceProfile? _serviceProfile;

    public Task StartAsync(CancellationToken cancellationToken)
    {
        if (!_options.Enabled)
        {
            _logger.LogInformation("mDNS discovery broadcast disabled by configuration.");
            return Task.CompletedTask;
        }

        if (_options.Port <= 0)
        {
            _logger.LogWarning("mDNS discovery skipped because configured port is invalid: {Port}", _options.Port);
            return Task.CompletedTask;
        }

        // Register network change handler
        NetworkChange.NetworkAddressChanged += OnNetworkChanged;

        // Start initial advertisement
        StartAdvertising();

        _appLifetime.ApplicationStopping.Register(() =>
        {
            try
            {
                _serviceDiscovery?.Dispose();
            }
            catch (Exception ex)
            {
                _logger.LogWarning(ex, "Failed to dispose mDNS service discovery cleanly.");
            }
        });

        return Task.CompletedTask;
    }

    public Task StopAsync(CancellationToken cancellationToken)
    {
        // Unregister network change handler
        NetworkChange.NetworkAddressChanged -= OnNetworkChanged;

        // Cancel any pending restart
        _restartCts?.Cancel();
        _restartCts?.Dispose();

        // Stop advertising
        _serviceDiscovery?.Dispose();
        _serviceDiscovery = null;
        _serviceProfile = null;

        _restartSemaphore.Dispose();

        return Task.CompletedTask;
    }

    private void OnNetworkChanged(object? sender, EventArgs e)
    {
        _logger.LogInformation("Network address changed, scheduling mDNS restart.");

        // Cancel previous pending restart
        _restartCts?.Cancel();
        _restartCts?.Dispose();

        // Create new CTS for this restart attempt
        _restartCts = new CancellationTokenSource();
        var cts = _restartCts;

        // Schedule restart after 500ms debounce
        _ = Task.Run(async () =>
        {
            try
            {
                await Task.Delay(500, cts.Token);
            }
            catch (OperationCanceledException)
            {
                return; // debounced away
            }

            await RestartAdvertising();
        });
    }

    private void StartAdvertising()
    {
        var instanceName = string.IsNullOrWhiteSpace(_options.InstanceName)
            ? Environment.MachineName
            : _options.InstanceName;

        _serviceProfile = new ServiceProfile(instanceName, _options.ServiceType, (ushort)_options.Port);
        _serviceProfile.AddProperty("name", Environment.MachineName);
        _serviceProfile.AddProperty("version", _options.Version ?? "1.0.0.0");
        _serviceProfile.AddProperty("path", _options.StatusPath);
        _serviceProfile.AddProperty("proto", _options.Protocol);

        _serviceDiscovery = new ServiceDiscovery();
        _serviceDiscovery.Advertise(_serviceProfile);

        _logger.LogInformation(
            "mDNS service advertised. Instance={InstanceName} ServiceType={ServiceType} Port={Port}",
            instanceName,
            _options.ServiceType,
            _options.Port);
    }

    private async Task RestartAdvertising()
    {
        await _restartSemaphore.WaitAsync();
        try
        {
            _logger.LogInformation("Network address changed, restarting mDNS advertisement.");

            // Stop current advertising
            _serviceDiscovery?.Dispose();
            _serviceDiscovery = null;
            _serviceProfile = null;

            // Restart advertising
            StartAdvertising();
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Failed to restart mDNS advertisement after network change.");
        }
        finally
        {
            _restartSemaphore.Release();
        }
    }

    // For testing: trigger network change manually
    public void TriggerNetworkChanged()
    {
        OnNetworkChanged(null, EventArgs.Empty);
    }
}

public sealed class DiscoveryOptions
{
    public bool Enabled { get; set; } = true;

    public string ServiceType { get; set; } = "_maimai-home._tcp";

    public string InstanceName { get; set; } = Environment.MachineName;

    public int Port { get; set; } = 8765;

    public string StatusPath { get; set; } = "/api/status";

    public string Protocol { get; set; } = "http";

    public string? Version { get; set; }
}