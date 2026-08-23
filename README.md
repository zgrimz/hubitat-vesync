# VeSync Integration for Hubitat

A Hubitat Elevation integration for VeSync / Levoit / Etekcity smart home devices — air
purifiers, humidifiers, bulbs, outlets, tower fans, switches, and dimmers — via the VeSync
cloud API.

This is an unofficial integration. It is not affiliated with, endorsed by, or supported by
VeSync, Levoit, or Etekcity.

## Supported Devices

### Verified

Hardware I own and actively run this integration against:

| Device | Model | Driver |
|---|---|---|
| Air Purifier | Core200S-P | `vesync-air-purifier.groovy` |
| Air Purifier | Core400S-P | `vesync-air-purifier.groovy` |
| Air Quality Sensor | (auto-created by Core400S-P) | `vesync-air-quality-sensor.groovy` |
| Humidifier | Superior6000S (LEH-S601S) | `vesync-humidifier.groovy` |

### Implemented, not yet verified

Support for these is written and follows the same API patterns, but I don't own the hardware and
haven't been able to test it. If you run one of these, I'd genuinely like to hear how it goes —
open an issue with a debug log either way, working or not.

**Air Purifiers** — Core300S, Core600S, Vital100S, Vital200S, LAP-C201S, LAP-C202S, LAP-C301S,
LAP-C302S, LAP-C401S, LAP-C601S, LAP-V201S, LAP-EL551S, LV-PUR131S, LV-RH131S

**Humidifiers** — Classic200S, Classic300S, Dual200S, LV600S, OasisMist, OasisMist600S,
OasisMist1000S, and other LUH/LEH series models

**Smart Bulbs** — ESL100 (white), ESL100CW (tunable white), ESL100MC (RGB), XYD0001 (RGB)

**Smart Outlets** — ESO15-TB, ESW15-USA, ESW03-USA, ESW01-EU, ESW10-USA, wifi-switch-1.3

**Tower Fans** — LTF-F422S series

**Wall Switches & Dimmers** — ESWL01, ESWL03 (switches), ESWD16 (dimmer with RGB indicator)

## Installation

### Step 1: Install the App

1. Go to **Apps Code** in your Hubitat hub
2. Click **+ New App**
3. Copy and paste the contents of `apps/vesync-integration.groovy`
4. Click **Save**

### Step 2: Install the Drivers

For each driver you need, repeat the following:

1. Go to **Drivers Code** in your Hubitat hub
2. Click **+ New Driver**
3. Copy and paste the contents of the driver file
4. Click **Save**

**Required drivers based on your devices:**
- `drivers/vesync-air-purifier.groovy` — For air purifiers
- `drivers/vesync-humidifier.groovy` — For humidifiers
- `drivers/vesync-light.groovy` — For smart bulbs
- `drivers/vesync-outlet.groovy` — For smart outlets
- `drivers/vesync-fan.groovy` — For tower fans
- `drivers/vesync-switch.groovy` — For basic wall switches
- `drivers/vesync-dimmer.groovy` — For dimmer switches
- `drivers/vesync-air-quality-sensor.groovy` — Required alongside the purifier driver if you
  have a Core300S/400S/600S, Vital, or LAP- series purifier. The app auto-creates an air quality
  child device for those models, and discovery logs an error if this driver isn't installed.

### Step 3: Configure the App

1. Go to **Apps** in your Hubitat hub
2. Click **+ Add User App**
3. Select **VeSync Integration**
4. Enter your VeSync credentials:
   - Email address
   - Password
   - Country code
5. Click **Test Authentication** to verify
6. Go to **Manage Devices** to discover and install your devices

## Features

### Device Discovery
The app automatically discovers all VeSync devices associated with your account. You can select
which devices to install.

### Automatic Polling
Device states are automatically updated at a configurable interval (default: 2 minutes).

### Device Capabilities

#### Air Purifiers
- On/Off control
- Fan speed control (1-4 levels)
- Mode selection (Manual, Auto, Sleep, Pet, Turbo)
- Air quality monitoring (PM2.5, PM10)
- Filter life tracking
- Child lock control
- Display on/off

#### Humidifiers
- On/Off control
- Target humidity setting (30-80%)
- Mist level control
- Mode selection (Manual, Auto, Sleep)
- Current humidity reading
- Water level monitoring
- Night light control
- Drying mode (Superior6000S) — implemented, not yet verified

#### Smart Bulbs
- On/Off control
- Brightness (0-100%)
- Color temperature (2700K-6500K)
- RGB color control (for color bulbs)

#### Smart Outlets
- On/Off control
- Power monitoring (watts)
- Voltage monitoring
- Energy tracking (kWh)

#### Fans
- On/Off control
- Speed control (1-12 levels)
- Oscillation control
- Mode selection (Normal, Auto, Sleep, Turbo)
- Timer support

### Exclusions
You can exclude devices by:
- Device type (purifier, humidifier, bulb, etc.)
- Device name (comma-separated list)

## Troubleshooting

### Authentication Issues
- Verify your VeSync email and password
- Ensure you selected the correct country
- Try clicking "Re-authenticate" in the app

### Devices Not Discovered
- Click "Discover Devices" in the device management page
- Check that your devices appear in the VeSync mobile app
- Verify the device types aren't excluded in settings

### Device Not Responding
- Check the device's connection status in the VeSync app
- Try clicking "Refresh" on the device page
- Verify your hub has internet connectivity

### Debug Logging
Enable debug logging in the app or driver preferences to see detailed logs:
1. Open the app or device
2. Enable "Debug Logging"
3. Check Logs for detailed information

## API Information

This integration communicates with the VeSync cloud API:
- US: `https://smartapi.vesync.com`
- EU: `https://smartapi.vesync.eu`

The integration uses JWT token authentication with automatic refresh before expiration.

The VeSync cloud API is undocumented and unsupported for third-party use. It can change without
notice, and using it may not be consistent with VeSync's terms of service.

## Credits

The VeSync cloud API is undocumented. Working out the protocol — endpoints, request payloads, and
device model identifiers — would have been considerably harder without these projects, which I
referred to while building this integration:

- **[tsvesync](https://github.com/mickgiles/tsvesync)** and
  **[homebridge-tsvesync](https://github.com/mickgiles/homebridge-tsvesync)** by Mick Giles (MIT)
- **[pyvesync](https://github.com/webdjoe/pyvesync)** (MIT), the original reverse-engineering
  effort that most VeSync libraries descend from

Neither project is affiliated with Hubitat or with this integration. See [NOTICE](NOTICE) for
their full license texts.

## Version History

### 1.0.0
- Initial release
- Verified on Core200S-P, Core400S-P, and Superior6000S
- Drivers for bulbs, outlets, tower fans, switches, and dimmers included but not yet verified
- Automatic device discovery
- JWT token management with auto-refresh
- Configurable polling interval
- Device exclusion support

## License

Licensed under the Apache License, Version 2.0. See [LICENSE](LICENSE).
