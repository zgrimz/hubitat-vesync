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
Repeated refresh requests for the same device are combined, including refreshes after commands.
Different devices retain their own scheduled refreshes. Status reads are spaced at least five
seconds apart per device and back off from 15 seconds to five minutes after failures. HTTP
requests time out after 15 seconds; a 30-second guard recovers if a status callback is lost.
Commands are still submitted immediately. These scheduling guards use Hubitat's `singleThreaded`
app option, available on platform 2.2.9 and later.

### Device Capabilities

#### Air Purifiers
- On/Off control
- Fan speed control, matched to your model's own level count (3 or 4 speeds)
- Standard Hubitat `FanControl` speeds, so dashboard fan tiles, Alexa, and Google all work
- Mode selection (Manual, Auto, Sleep, Pet, Turbo)
- Air quality monitoring (PM2.5, PM10)
- Filter life tracking
- Child lock control
- Display on/off

#### Air Quality Sensors
Auto-created alongside purifiers that have an air quality sensor.
- PM2.5 and PM10 readings
- Air quality index, level, and a plain-language description
- Configurable alert threshold

#### Humidifiers
- On/Off control
- Target humidity setting (30-80%)
- Mist level control
- Mode selection (Manual, Auto, Sleep)
- Current humidity reading
- Water level monitoring
- Night light control — implemented, not yet verified
- Auto stop at target humidity — implemented, not yet verified
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
- Energy tracking (kWh), with a resettable counter
- Derived amperage and an "in use" indicator with a configurable threshold
- Night light mode — implemented, not yet verified

#### Fans
- On/Off control
- Speed control (1-12 levels)
- Standard Hubitat `FanControl` speeds
- Oscillation control
- Mode selection (Normal, Auto, Sleep, Turbo)
- Timer support — implemented, not yet verified

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

### After Updating the Code
Save the app and affected driver code, then open the installed VeSync app and click **Done**
to rebuild its schedules. Update the app and drivers together. Existing devices are retained.
For troubleshooting hub slowdowns, temporarily disable the installed VeSync app and its devices
and compare stability before re-enabling them; a code review alone cannot identify a hub crash.

### Local Reliability Checks
From this repository, run `groovy tests/reliability.groovy`. The tests execute the app and driver
methods with mocked Hubitat scheduling, HTTP callbacks, and devices. They cover duplicate
refreshes, lost/late callbacks, failure backoff, power state, and level-change limits. They do
not replace a test on Hubitat hardware or verify the live VeSync API.

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

### 1.1.1

- Coalesce refreshes per device, reject late responses, recover lost callbacks, and bound
  status-request frequency with explicit HTTP timeouts and failure backoff
- Preserve reported purifier/fan power when an off device retains a speed setting
- Bound light/dimmer ramps and stop them when brightness commands fail
- Add local regression checks for scheduling, callbacks, power state, and ramp limits

Update the parent app and any installed **Air Purifier**, **Fan**, **Light**, and **Dimmer**
drivers to 1.1.1, then open the installed VeSync app and click **Done** to reset old schedules.
Other drivers remain at 1.1.0 and do not need an update for this patch.

### 1.1.0

Reliability and accuracy work across the app and every driver.

- Air quality sensors now report level, description, and last-update time alongside PM2.5
- Outlets now report amperage and an "in use" state, and `resetEnergy` offsets the reading
- Filter life, mist level, and display state now report their true value, including zero
- Installing several devices at once refreshes all of them, not just the last
- Commands that fail now resync the device state instead of leaving the UI optimistic
- Purifiers and fans publish `supportedFanSpeeds` and use the standard `FanControl` ENUM,
  so dashboard fan tiles, Alexa, and Google see the right speeds for the model
- Fan speed names map to each model's real level count, so 3-speed purifiers reach every speed
- Night light, auto stop, timer, night light mode, and dimmer indicator commands now send a
  payload — all unverified, and an unsupported command is now logged explicitly
- Requests carry the device's own region and the hub's time zone
- Maximum speed comes from one model table shared by app and driver, with a user override
- Polling staggers its requests rather than issuing them all at once
- Level-change ramps step every 2s to stay clear of cloud rate limits

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
