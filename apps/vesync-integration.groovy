/**
 *  VeSync Integration
 *
 *  Copyright 2025-2026 Zachary Grimshaw
 *
 *  Protocol details for the VeSync cloud API were determined with reference to the
 *  tsvesync project (https://github.com/mickgiles/tsvesync, MIT, Copyright (c) 2024
 *  Mick Giles) and pyvesync (https://github.com/webdjoe/pyvesync, MIT). See NOTICE.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *  for the specific language governing permissions and limitations under the License.
 *
 *  VeSync Integration - Parent App
 *
 *  Integrates VeSync/Levoit/Etekcity smart home devices into Hubitat
 *  Supports: Air Purifiers, Humidifiers, Smart Bulbs, Outlets, Fans, and Switches
 *
 */

import groovy.json.JsonSlurper
import groovy.json.JsonOutput
import groovy.transform.Field
import java.security.MessageDigest

@Field static final String VERSION = "1.1.1"
@Field static final String NAMESPACE = "vesync"

@Field static final Integer HTTP_TIMEOUT_SECONDS = 15
@Field static final Long REFRESH_GUARD_MS = 30000L
@Field static final Long MIN_REFRESH_INTERVAL_MS = 5000L
@Field static final Long MAX_REFRESH_BACKOFF_MS = 300000L

// API Endpoints
@Field static final String API_BASE_URL_US = "https://smartapi.vesync.com"
@Field static final String API_BASE_URL_EU = "https://smartapi.vesync.eu"

// Client identification sent with every request
@Field static final String APP_VERSION = "2.8.6"
@Field static final String PHONE_BRAND = "SM N9005"
@Field static final String PHONE_OS = "Android"

// EU Country Codes
@Field static final List<String> EU_COUNTRIES = ["AT", "BE", "BG", "HR", "CY", "CZ", "DK", "EE", "FI", "FR",
    "DE", "GR", "HU", "IE", "IT", "LV", "LT", "LU", "MT", "NL", "PL", "PT", "RO", "SK", "SI", "ES", "SE", "GB"]

// Device Type Mappings
@Field static final Map DEVICE_TYPE_MAP = [
    // Air Purifiers
    "Core200S": "purifier", "Core300S": "purifier", "Core400S": "purifier", "Core600S": "purifier",
    "Vital100S": "purifier", "Vital200S": "purifier",
    "LAP-C201S": "purifier", "LAP-C202S": "purifier", "LAP-C301S": "purifier", "LAP-C302S": "purifier",
    "LAP-C401S": "purifier", "LAP-C601S": "purifier",
    "LAP-V201S": "purifier", "LAP-EL551S": "purifier",
    "LV-PUR131S": "purifier", "LV-RH131S": "purifier",
    // Humidifiers
    "Classic200S": "humidifier", "Classic300S": "humidifier",
    "Dual200S": "humidifier", "LV600S": "humidifier",
    "OasisMist": "humidifier", "OasisMist600S": "humidifier", "OasisMist1000S": "humidifier",
    "Superior6000S": "humidifier",
    "LUH-": "humidifier", "LEH-": "humidifier",
    // Bulbs
    "ESL100": "bulb", "ESL100CW": "bulb", "ESL100MC": "bulb", "XYD0001": "bulb",
    "ESWD16": "dimmer",
    // Outlets
    "ESO15-TB": "outlet", "ESW15-USA": "outlet", "ESW03-USA": "outlet",
    "ESW01-EU": "outlet", "ESW10-USA": "outlet", "wifi-switch-1.3": "outlet",
    // Fans
    "LTF-F422S": "fan",
    // Switches
    "ESWL01": "switch", "ESWL03": "switch"
]

// Per-category API configuration. Adding a device family means adding one entry here and
// one update<Category>Device() method - the endpoints, methods and payload shape all live
// in this table rather than being spread across parallel switch statements.
@Field static final Map CATEGORY_CONFIG = [
    "purifier": [
        "driver":          "VeSync Air Purifier",
        "bypassV2":        true,
        "statusMethod":    "getPurifierStatus",
        "statusEndpoint":  "/cloud/v2/deviceManaged/bypassV2",
        "commandEndpoint": "/cloud/v2/deviceManaged/bypassV2",
        "statusPayload":   ["type": "air", "id": 0]
    ],
    "humidifier": [
        "driver":          "VeSync Humidifier",
        "bypassV2":        true,
        "statusMethod":    "getHumidifierStatus",
        "statusEndpoint":  "/cloud/v2/deviceManaged/bypassV2",
        "commandEndpoint": "/cloud/v2/deviceManaged/bypassV2",
        "statusPayload":   [:]
    ],
    "fan": [
        "driver":          "VeSync Fan",
        "bypassV2":        true,
        "statusMethod":    "getTowerFanStatus",
        "statusEndpoint":  "/cloud/v2/deviceManaged/bypassV2",
        "commandEndpoint": "/cloud/v2/deviceManaged/bypassV2",
        "statusPayload":   [:]
    ],
    "bulb": [
        "driver":          "VeSync Light",
        "bypassV2":        false,
        "statusMethod":    "getLightStatus",
        "statusEndpoint":  "/SmartBulb/v1/device/devicedetail",
        "commandEndpoint": "/SmartBulb/v1/device/devicestatus"
    ],
    "dimmer": [
        // UNVERIFIED - no hardware. ESWD16 uses the SmartBulb endpoints here because that
        // is what this integration has always sent. /dimmer/v1/device/... is the more
        // likely correct path, but it is left alone rather than swapped on a guess.
        "driver":          "VeSync Dimmer",
        "bypassV2":        false,
        "statusMethod":    "getLightStatus",
        "statusEndpoint":  "/SmartBulb/v1/device/devicedetail",
        "commandEndpoint": "/SmartBulb/v1/device/devicestatus"
    ],
    "outlet": [
        "driver":          "VeSync Outlet",
        "bypassV2":        false,
        "statusMethod":    "getOutletStatus",
        "statusEndpoint":  "/v1/device/getOutletStatus",
        "commandEndpoint": "/10a/v1/device/devicestatus"
    ],
    "switch": [
        "driver":          "VeSync Switch",
        "bypassV2":        false,
        "statusMethod":    "getSwitchStatus",
        "statusEndpoint":  "/inwallswitch/v1/device/devicedetail",
        "commandEndpoint": "/inwallswitch/v1/device/devicestatus"
    ]
]

// Maximum fan / mist speed level by model prefix. Single source of truth: stamped onto each
// child device at creation and read back by the driver, so the app and driver cannot drift.
@Field static final Map MAX_SPEED_MAP = [
    "Core200S": 3, "Core300S": 3, "Core400S": 4, "Core600S": 4,
    "LV-PUR131S": 3, "LV-RH131S": 3,
    "Vital100S": 4, "Vital200S": 4,
    "LAP-C201S": 3, "LAP-C202S": 3, "LAP-C301S": 3, "LAP-C302S": 3,
    "LAP-C401S": 4, "LAP-C601S": 4, "LAP-V201S": 4, "LAP-EL551S": 4,
    "LTF-F422S": 12
]

@Field static final Integer DEFAULT_MAX_SPEED = 4

// Models that name their API fields differently from the rest of the family. Looked up by
// deviceType prefix; anything not listed gets the defaults in getQuirks().
@Field static final Map DEVICE_QUIRKS = [
    // Superior 6000S reports and accepts numeric power, uses workMode rather than mode,
    // names its auto mode "autoPro", and takes camelCase targetHumidity.
    "LEH-S601S": ["numericPower": true, "modeKey": "workMode",
                  "autoMode": "autoPro", "targetHumidityKey": "targetHumidity"]
]

@Field static final Map DEFAULT_QUIRKS = [
    "numericPower": false, "modeKey": "mode",
    "autoMode": "auto", "targetHumidityKey": "target_humidity"
]

definition(
    name: "VeSync Integration",
    namespace: NAMESPACE,
    author: "VeSync Hubitat Integration",
    description: "Integrates VeSync/Levoit/Etekcity smart home devices",
    category: "Convenience",
    iconUrl: "",
    iconX2Url: "",
    iconX3Url: "",
    singleInstance: true,
    // Serialize the short request/response handlers so per-device refresh guards cannot race.
    // HTTP itself remains asynchronous. Requires Hubitat platform 2.2.9 or later.
    singleThreaded: true,
    importUrl: ""
)

preferences {
    page(name: "mainPage")
    page(name: "credentialsPage")
    page(name: "devicePage")
    page(name: "advancedPage")
}

def mainPage() {
    dynamicPage(name: "mainPage", title: "VeSync Integration", install: true, uninstall: true) {
        section {
            paragraph "Version: ${VERSION}"
        }

        if (state.token) {
            def tokenValid = isTokenValid()
            def expiry = atomicState.tokenExpiry
            def expiryText = expiry ? new Date(expiry).format("yyyy-MM-dd HH:mm:ss") : "Unknown"
            section("Status") {
                paragraph "Connected to VeSync as: ${settings.username}"
                if (tokenValid) {
                    paragraph "Token expires: ${expiryText}"
                } else {
                    paragraph "<b style='color:red'>TOKEN EXPIRED</b> - Token expired on: ${expiryText}. Click 'Re-authenticate' below."
                }
            }
            section("Devices") {
                href "devicePage", title: "Manage Devices", description: "Discover and manage VeSync devices"
            }
        }

        section("Configuration") {
            href "credentialsPage", title: "VeSync Credentials", description: isTokenValid() ? "Connected" : (state.token ? "Token Expired" : "Configure credentials")
            href "advancedPage", title: "Advanced Settings", description: "Configure polling interval and debug options"
        }

        section("Actions") {
            input "refreshDevices", "button", title: "Refresh All Devices"
            input "reAuthenticate", "button", title: "Re-authenticate"
        }
    }
}

def credentialsPage() {
    dynamicPage(name: "credentialsPage", title: "VeSync Credentials") {
        section {
            input "username", "text", title: "VeSync Email", required: true, submitOnChange: true
            input "password", "password", title: "VeSync Password", required: true, submitOnChange: true
            input "countryCode", "enum", title: "Country", required: true, defaultValue: "US",
                options: ["US": "United States", "CA": "Canada", "GB": "United Kingdom", "DE": "Germany",
                         "FR": "France", "IT": "Italy", "ES": "Spain", "AU": "Australia", "NZ": "New Zealand",
                         "JP": "Japan", "CN": "China", "KR": "South Korea", "IN": "India", "BR": "Brazil",
                         "MX": "Mexico", "NL": "Netherlands", "BE": "Belgium", "AT": "Austria", "CH": "Switzerland",
                         "SE": "Sweden", "NO": "Norway", "DK": "Denmark", "FI": "Finland", "PL": "Poland",
                         "CZ": "Czech Republic", "HU": "Hungary", "RO": "Romania", "GR": "Greece", "PT": "Portugal",
                         "IE": "Ireland", "IL": "Israel", "AE": "UAE", "SA": "Saudi Arabia", "SG": "Singapore",
                         "MY": "Malaysia", "TH": "Thailand", "PH": "Philippines", "ID": "Indonesia", "VN": "Vietnam",
                         "TW": "Taiwan", "HK": "Hong Kong", "RU": "Russia", "UA": "Ukraine", "TR": "Turkey",
                         "ZA": "South Africa", "EG": "Egypt", "NG": "Nigeria", "KE": "Kenya", "CO": "Colombia",
                         "AR": "Argentina", "CL": "Chile", "PE": "Peru", "VE": "Venezuela"]
        }

        section {
            input "testAuth", "button", title: "Test Authentication"
            if (state.authMessage) {
                paragraph state.authMessage
            }
        }
    }
}

def devicePage() {
    dynamicPage(name: "devicePage", title: "Device Management") {
        if (!isTokenValid()) {
            section {
                paragraph "Please configure credentials first or re-authenticate (token may be expired)"
            }
            return
        }

        section("Discovered Devices") {
            input "discoverDevices", "button", title: "Discover Devices"

            if (state.discoveredDevices) {
                state.discoveredDevices.each { device ->
                    def deviceType = getDeviceCategory(device.deviceType)
                    def isInstalled = getChildDevice(device.cid) != null
                    paragraph "${device.deviceName} (${device.deviceType}) - ${deviceType} ${isInstalled ? '[Installed]' : ''}"
                }
            }
        }

        section("Device Selection") {
            def deviceOptions = state.discoveredDevices?.collectEntries { d ->
                [(d.cid): "${d.deviceName} (${d.deviceType})"]
            } ?: [:]
            input "selectedDevices", "enum", title: "Select Devices to Install", multiple: true, options: deviceOptions
        }

        section {
            input "installSelected", "button", title: "Install Selected Devices"
            input "removeAll", "button", title: "Remove All Devices"
        }

        section("Installed Devices") {
            def children = getChildDevices()
            if (children) {
                children.each { child ->
                    paragraph "${child.label ?: child.name} (${child.deviceNetworkId})"
                }
            } else {
                paragraph "No devices installed"
            }
        }
    }
}

def advancedPage() {
    dynamicPage(name: "advancedPage", title: "Advanced Settings") {
        section("Polling") {
            input "pollingInterval", "enum", title: "Polling Interval", defaultValue: "120",
                options: ["60": "1 minute", "120": "2 minutes", "300": "5 minutes", "600": "10 minutes"]
        }

        section("Logging") {
            input "debugLogging", "bool", title: "Enable Debug Logging", defaultValue: false
            input "descriptionLogging", "bool", title: "Enable Description Logging", defaultValue: true
        }

        section("Exclusions") {
            input "excludedTypes", "enum", title: "Exclude Device Types", multiple: true,
                options: ["purifier": "Air Purifiers", "humidifier": "Humidifiers", "bulb": "Bulbs",
                         "dimmer": "Dimmers", "outlet": "Outlets", "fan": "Fans", "switch": "Switches"]
            input "excludedNames", "text", title: "Exclude Devices by Name (comma-separated)", required: false
        }
    }
}

def installed() {
    logDebug "Installed VeSync Integration"
    initialize()
}

def updated() {
    logDebug "Updated VeSync Integration"
    unschedule()
    initialize()
}

def uninstalled() {
    logDebug "Uninstalling VeSync Integration"
    unschedule()
    getChildDevices().each { deleteChildDevice(it.deviceNetworkId) }
}

def initialize() {
    logDebug "Initializing VeSync Integration"

    // Authenticate if no token or token is expired
    if (settings.username && settings.password && !isTokenValid()) {
        logInfo "Token missing or expired - authenticating"
        authenticate()
    }

    backfillDeviceData()
    // updated() removed queued jobs; do not leave their persisted guards behind.
    def deviceIds = getChildDevices().collect { it.deviceNetworkId }
    atomicState.keySet().findAll { it.toString().startsWith("refreshControl_") }.each { key ->
        if (!deviceIds.contains(key.toString().substring("refreshControl_".length()))) {
            atomicState.remove(key)
        }
    }
    deviceIds.each { cid ->
        def control = getRefreshControl(cid)
        control.queued = false
        control.inFlight = null
        control.followUp = false
        control.sequence = (control.sequence ?: 0L) + 1L
        saveRefreshControl(cid, control)
    }

    if (isTokenValid()) {
        scheduleTokenRefresh()
        schedulePolling()
    }
}

// Devices created by earlier versions have no maxSpeed data value. Without this, a 3-speed
// Core200S would silently start reporting itself as 4-speed after an upgrade.
def backfillDeviceData() {
    getChildDevices().each { child ->
        if (child.deviceNetworkId.endsWith("-AQ")) return

        def deviceType = child.getDataValue("deviceType")
        if (!deviceType) return

        if (!child.getDataValue("maxSpeed")) {
            child.updateDataValue("maxSpeed", getMaxSpeed(deviceType).toString())
            logDebug "Backfilled maxSpeed for ${child.label}"
        }

        if (getDeviceCategory(deviceType) in ["purifier", "fan"]) {
            child.publishSupportedSpeeds()
        }
    }
}

// Button Handlers
def appButtonHandler(btn) {
    switch(btn) {
        case "testAuth":
            authenticate()
            break
        case "discoverDevices":
            discoverDevices()
            break
        case "installSelected":
            installSelectedDevices()
            break
        case "removeAll":
            removeAllDevices()
            break
        case "refreshDevices":
            refreshAllDevices()
            break
        case "reAuthenticate":
            state.token = null
            state.accountId = null
            atomicState.tokenExpiry = null
            atomicState.lastAuthAttempt = 0  // Reset cooldown to allow immediate auth
            authenticate()
            break
    }
}

// Authentication
def authenticate() {
    // Prevent multiple simultaneous auth requests using atomicState for consistency
    def lastAuthAttempt = atomicState.lastAuthAttempt ?: 0
    def timeSinceLastAuth = now() - lastAuthAttempt

    // Don't allow auth more than once per 5 seconds
    if (timeSinceLastAuth < 5000) {
        logDebug "Authentication attempted ${timeSinceLastAuth}ms ago, skipping (cooldown: 5000ms)"
        return
    }

    logInfo "Authenticating with VeSync API"
    atomicState.lastAuthAttempt = now()

    if (!settings.username || !settings.password) {
        state.authMessage = "Please enter username and password"
        logError "Authentication failed: missing credentials"
        return
    }

    def apiUrl = getApiUrl()

    def body = buildBaseBody([
        email: settings.username,
        password: hashPassword(settings.password),
        method: "login",
        token: "",
        accountID: "",
        devToken: "",
        userType: "1"
    ])

    def params = [
        uri: "${apiUrl}/cloud/v1/user/login",
        contentType: "application/json",
        timeout: HTTP_TIMEOUT_SECONDS,
        body: JsonOutput.toJson(body)
    ]

    logDebug "Auth request to: ${params.uri}"

    try {
        asynchttpPost("handleAuthResponse", params)
    } catch (e) {
        state.authMessage = "Authentication error: ${e.message}"
        logError "Authentication error: ${e.message}"
    }
}

def handleAuthResponse(resp, data) {
    logDebug "Auth response status: ${resp.status}"

    if (resp.status == 200) {
        def jsonData = null
        try {
            jsonData = new JsonSlurper().parseText(resp.data)
        } catch (e) {
            state.authMessage = "Authentication error parsing response: ${e.message}"
            logError "Auth response parse error: ${e.message}"
            return
        }

        logDebug "Auth response code: ${jsonData?.code}"

        if (jsonData?.code == 0 && jsonData?.result) {
            // atomicState writes through immediately, so the scheduling calls below can read
            // the new expiry directly - no runIn() delay needed to "let state persist".
            def newExpiry = now() + (30L * 24L * 60L * 60L * 1000L) // 30 days - Long avoids int overflow
            atomicState.tokenExpiry = newExpiry
            state.token = jsonData.result.token
            state.accountId = jsonData.result.accountID
            state.authMessage = "Authentication successful!"
            logInfo "Successfully authenticated with VeSync - new token expires: ${new Date(newExpiry)}"

            scheduleTokenRefresh()
            schedulePolling()
        } else {
            state.authMessage = "Authentication failed: ${jsonData?.msg ?: 'Unknown error (code: ' + jsonData?.code + ')'}"
            logError "Authentication failed: ${jsonData?.msg ?: jsonData}"
        }
    } else {
        state.authMessage = "Authentication failed: HTTP ${resp.status}"
        logError "Authentication failed: status=${resp.status}"
    }
}

// Every VeSync request carries the same client-identification envelope. Callers pass only
// the fields that differ; `extra` wins on collision so authenticate() can blank the token.
def buildBaseBody(Map extra = [:]) {
    def body = [
        acceptLanguage: "en",
        appVersion: APP_VERSION,
        phoneBrand: PHONE_BRAND,
        phoneOS: PHONE_OS,
        timeZone: location.timeZone?.ID ?: "America/New_York",
        accountID: state.accountId,
        token: state.token,
        traceId: now().toString()
    ]
    return body + extra
}

// Returns the first non-null value among `keys`. Unlike ?:, a legitimate 0 or false does
// not fall through to the next candidate - which is what made a 0% filter read as 100%.
def pick(Map src, List keys, def fallback = null) {
    for (k in keys) {
        if (src?.get(k) != null) return src[k]
    }
    return fallback
}

def hashPassword(password) {
    MessageDigest md = MessageDigest.getInstance("MD5")
    md.update(password.getBytes())
    byte[] digest = md.digest()
    return digest.collect { String.format('%02x', it) }.join()
}

def getApiUrl() {
    def country = settings.countryCode ?: "US"
    return EU_COUNTRIES.contains(country) ? API_BASE_URL_EU : API_BASE_URL_US
}

def isTokenValid() {
    // Check both token existence AND expiration
    if (!state.token) {
        return false
    }
    // atomicState is the only writer of tokenExpiry - it survives async callbacks intact
    def expiry = atomicState.tokenExpiry
    if (!expiry) {
        // No expiry recorded, token is invalid
        return false
    }
    // Token is valid if it hasn't expired yet (with 1 minute buffer)
    def isValid = expiry > (now() + 60000)
    if (!isValid) {
        logDebug "Token has expired (expiry: ${new Date(expiry)}, now: ${new Date()})"
    }
    return isValid
}

def scheduleTokenRefresh() {
    // Refresh token 5 days before expiry - use Long to avoid integer overflow
    def expiry = atomicState.tokenExpiry
    def refreshTime = expiry ? expiry - (5L * 24L * 60L * 60L * 1000L) : now() + (25L * 24L * 60L * 60L * 1000L)
    def delay = refreshTime - now()

    if (delay > 0) {
        runIn((delay / 1000).toInteger(), "authenticate")
        def daysUntilRefresh = (delay / (24L * 60L * 60L * 1000L)).toDouble().round(1)
        logDebug "Token refresh scheduled in ${daysUntilRefresh} days"
    } else {
        authenticate()
    }
}

def schedulePolling() {
    // Only schedule polling if we have a valid token
    if (!isTokenValid()) {
        logDebug "Not scheduling polling - not authenticated or token expired"
        return
    }

    unschedule("refreshAllDevices")

    def intervalSeconds = (settings.pollingInterval ?: "120").toInteger()

    // Convert seconds to minutes for cron (minimum 1 minute)
    def intervalMinutes = Math.max(1, Math.round(intervalSeconds / 60))

    // Use cron expression with minutes: "0 */N * * * ?" = every N minutes
    def cronExpr = "0 */${intervalMinutes} * * * ?"
    schedule(cronExpr, "refreshAllDevices")
    logInfo "Polling scheduled every ${intervalMinutes} minute(s)"
}

// Device Discovery
def discoverDevices() {
    logDebug "Discovering VeSync devices"

    if (!isTokenValid()) {
        logError "Not authenticated or token expired - triggering re-authentication"
        authenticate()
        return
    }

    def apiUrl = getApiUrl()

    def body = buildBaseBody([
        method: "devices",
        pageNo: 1,
        pageSize: 100
    ])

    def params = [
        uri: "${apiUrl}/cloud/v1/deviceManaged/devices",
        contentType: "application/json",
        timeout: HTTP_TIMEOUT_SECONDS,
        body: JsonOutput.toJson(body)
    ]

    try {
        asynchttpPost("handleDiscoveryResponse", params)
    } catch (e) {
        logError "Device discovery error: ${e.message}"
    }
}

def handleDiscoveryResponse(resp, data) {
    logDebug "Discovery response status: ${resp.status}"

    if (resp.status == 200) {
        try {
            def jsonData = new JsonSlurper().parseText(resp.data)
            logDebug "Discovery response code: ${jsonData?.code}"

            if (jsonData?.code == 0 && jsonData?.result?.list) {
                def devices = jsonData.result.list
                state.discoveredDevices = devices.collect { d ->
                    [
                        cid: d.cid,
                        uuid: d.uuid,
                        deviceName: d.deviceName,
                        deviceType: d.deviceType,
                        deviceStatus: d.deviceStatus,
                        connectionStatus: d.connectionStatus,
                        configModule: d.configModule,
                        subDeviceNo: d.subDeviceNo,
                        deviceRegion: d.deviceRegion
                    ]
                }

                // Filter excluded types
                if (settings.excludedTypes) {
                    state.discoveredDevices = state.discoveredDevices.findAll { d ->
                        def category = getDeviceCategory(d.deviceType)
                        !settings.excludedTypes.contains(category)
                    }
                }

                // Filter excluded names
                if (settings.excludedNames) {
                    def excludedList = settings.excludedNames.split(",").collect { it.trim().toLowerCase() }
                    state.discoveredDevices = state.discoveredDevices.findAll { d ->
                        !excludedList.contains(d.deviceName.toLowerCase())
                    }
                }

                logInfo "Discovered ${state.discoveredDevices.size()} devices"
            } else {
                logError "Failed to get devices: ${jsonData?.msg ?: 'No devices returned (code: ' + jsonData?.code + ')'}"
            }
        } catch (e) {
            logError "Discovery response parse error: ${e.message}"
        }
    } else {
        logError "Device discovery failed: HTTP ${resp.status}"
    }
}

def getDeviceCategory(deviceType) {
    // Check exact match first
    if (DEVICE_TYPE_MAP.containsKey(deviceType)) {
        return DEVICE_TYPE_MAP[deviceType]
    }

    // Check prefix match
    def matchedType = DEVICE_TYPE_MAP.find { key, value ->
        deviceType.startsWith(key)
    }

    if (matchedType) {
        return matchedType.value
    }

    // Categorize by common patterns
    if (deviceType.contains("Core") || deviceType.contains("Vital") || deviceType.contains("LAP-") || deviceType.contains("PUR")) {
        return "purifier"
    }
    if (deviceType.contains("Humid") || deviceType.contains("LUH") || deviceType.contains("LEH") || deviceType.contains("Oasis") || deviceType.contains("LV600") || deviceType.contains("Classic") || deviceType.contains("Dual") || deviceType.contains("Superior")) {
        return "humidifier"
    }
    if (deviceType.contains("ESL") || deviceType.contains("XYD")) {
        return "bulb"
    }
    if (deviceType.contains("ESO") || (deviceType.contains("ESW") && !deviceType.contains("ESWL"))) {
        return "outlet"
    }
    if (deviceType.contains("LTF")) {
        return "fan"
    }
    if (deviceType.contains("ESWL")) {
        return "switch"
    }

    return "unknown"
}

// Single lookup for everything category-specific: driver name, endpoints, API method and
// whether the request needs the bypassV2 payload wrapper.
def getCategoryConfig(category) {
    return CATEGORY_CONFIG[category]
}

def getDriverName(category) {
    return CATEGORY_CONFIG[category]?.driver
}

// Maximum speed level for a model, by prefix match against MAX_SPEED_MAP.
def getMaxSpeed(deviceType) {
    if (!deviceType) return DEFAULT_MAX_SPEED
    def match = MAX_SPEED_MAP.find { prefix, max -> deviceType.startsWith(prefix) }
    return match ? match.value : DEFAULT_MAX_SPEED
}

// Field-naming differences for models that don't follow the family convention.
def getQuirks(deviceType) {
    if (!deviceType) return DEFAULT_QUIRKS
    def upper = deviceType.toUpperCase()
    def match = DEVICE_QUIRKS.find { model, quirks -> upper.contains(model) }
    return match ? (DEFAULT_QUIRKS + match.value) : DEFAULT_QUIRKS
}

// Device Management
def installSelectedDevices() {
    if (!settings.selectedDevices) {
        logDebug "No devices selected"
        return
    }

    settings.selectedDevices.each { cid ->
        def deviceInfo = state.discoveredDevices?.find { it.cid == cid }
        if (deviceInfo) {
            createChildDevice(deviceInfo)
        }
    }
}

def createChildDevice(deviceInfo) {
    def category = getDeviceCategory(deviceInfo.deviceType)
    def driverName = getDriverName(category)

    if (!driverName) {
        logError "Unknown device type: ${deviceInfo.deviceType}"
        return null
    }

    def existingDevice = getChildDevice(deviceInfo.cid)
    if (existingDevice) {
        logDebug "Device already exists: ${deviceInfo.deviceName}"
        return existingDevice
    }

    try {
        def child = addChildDevice(NAMESPACE, driverName, deviceInfo.cid, [
            name: driverName,
            label: deviceInfo.deviceName,
            isComponent: false
        ])

        child.updateDataValue("deviceType", deviceInfo.deviceType)
        child.updateDataValue("uuid", deviceInfo.uuid)
        child.updateDataValue("configModule", deviceInfo.configModule ?: "")
        child.updateDataValue("deviceRegion", deviceInfo.deviceRegion ?: "")
        child.updateDataValue("subDeviceNo", deviceInfo.subDeviceNo?.toString() ?: "0")
        child.updateDataValue("maxSpeed", getMaxSpeed(deviceInfo.deviceType).toString())

        // installed() ran inside addChildDevice, before maxSpeed was stamped, so the fan-like
        // drivers need a nudge to publish supportedFanSpeeds for the right level count.
        if (category in ["purifier", "fan"]) {
            child.publishSupportedSpeeds()
        }

        logInfo "Created device: ${deviceInfo.deviceName} (${driverName})"

        scheduleDeviceRefresh(deviceInfo.cid, 2000L)

        // Check if purifier has air quality sensor - create separate device
        if (category == "purifier" && hasAirQualitySensor(deviceInfo.deviceType)) {
            createAirQualitySensorDevice(deviceInfo)
        }

        return child
    } catch (e) {
        logError "Failed to create device ${deviceInfo.deviceName}: ${e.message}"
        return null
    }
}

def hasAirQualitySensor(deviceType) {
    def aqDevices = ["Core300S", "Core400S", "Core600S", "Vital100S", "Vital200S",
                     "LAP-C201S", "LAP-C202S", "LAP-C301S", "LAP-C302S", "LAP-C401S", "LAP-C601S",
                     "LAP-V201S", "LAP-EL551S"]
    return aqDevices.any { deviceType.startsWith(it) }
}

def createAirQualitySensorDevice(deviceInfo) {
    def sensorCid = "${deviceInfo.cid}-AQ"
    def existingDevice = getChildDevice(sensorCid)

    if (existingDevice) {
        return existingDevice
    }

    try {
        def child = addChildDevice(NAMESPACE, "VeSync Air Quality Sensor", sensorCid, [
            name: "VeSync Air Quality Sensor",
            label: "${deviceInfo.deviceName} Air Quality",
            isComponent: false
        ])

        child.updateDataValue("parentCid", deviceInfo.cid)
        child.updateDataValue("deviceType", deviceInfo.deviceType)

        logInfo "Created air quality sensor for: ${deviceInfo.deviceName}"
        return child
    } catch (e) {
        logError "Failed to create air quality sensor: ${e.message}"
        return null
    }
}

def removeAllDevices() {
    getChildDevices().each {
        atomicState.remove("refreshControl_${it.deviceNetworkId}".toString())
        deleteChildDevice(it.deviceNetworkId)
    }
    logInfo "Removed all child devices"
}

// Device Refresh
def refreshAllDevices() {
    logDebug "Refreshing all devices"

    if (!isTokenValid()) {
        logInfo "Token expired - re-authenticating before refresh"
        authenticate()
        return
    }

    // Skip AQ sensor devices - they get updated with their parent
    def targets = getChildDevices().findAll { !it.deviceNetworkId.endsWith("-AQ") }

    // Keep different devices independent, while coalescing repeat requests for each CID.
    targets.eachWithIndex { child, idx ->
        scheduleDeviceRefresh(child.deviceNetworkId, (idx + 1) * 400L)
    }

    logDebug "Scheduled refresh for ${targets.size()} device(s)"
}

// Public entry point used by child drivers and any jobs from an older app version.
def refreshChildDevice(data) {
    scheduleDeviceRefresh(data?.cid)
}

def getRefreshControl(cid) {
    return atomicState["refreshControl_${cid}".toString()] ?: [:]
}

def saveRefreshControl(cid, Map control) {
    atomicState["refreshControl_${cid}".toString()] = control
}

def refreshBackoffMillis(failures) {
    return Math.min(MAX_REFRESH_BACKOFF_MS, 15000L * (1L << Math.min(5, Math.max(0, failures - 1))))
}

def scheduleDeviceRefresh(cid, delayMs = 0L) {
    if (!cid || !getChildDevice(cid)) return
    def control = getRefreshControl(cid)
    def timestamp = now()

    // A scheduled job or callback can be lost during a reboot. Both guards expire.
    if (control.queued && timestamp < (control.dueAt ?: 0L) + REFRESH_GUARD_MS) return
    control.queued = false
    if (control.inFlight != null) {
        if (timestamp < (control.startedAt ?: 0L) + REFRESH_GUARD_MS) {
            control.followUp = true
            saveRefreshControl(cid, control)
            return
        }
        control.inFlight = null
        control.failures = Math.min(6, (control.failures ?: 0) + 1)
        control.retryAfter = timestamp + refreshBackoffMillis(control.failures)
    }

    def dueAt = Math.max(timestamp + Math.max(0L, delayMs as Long),
                        Math.max(control.retryAfter ?: 0L,
                                 (control.lastStartedAt ?: 0L) + MIN_REFRESH_INTERVAL_MS))
    control.sequence = (control.sequence ?: 0L) + 1L
    control.queued = true
    control.dueAt = dueAt
    control.followUp = false
    saveRefreshControl(cid, control)
    // overwrite:false is essential: a different CID must not cancel this device's job.
    runInMillis(Math.max(1L, dueAt - timestamp), "performDeviceRefresh",
        [overwrite: false, data: [cid: cid, sequence: control.sequence]])
}

def performDeviceRefresh(data) {
    def cid = data?.cid
    if (!cid) return
    def control = getRefreshControl(cid)
    if (!control.queued || control.sequence != data.sequence) return
    control.queued = false
    saveRefreshControl(cid, control)
    def child = getChildDevice(cid)
    if (!child) return
    getDeviceDetails(child, data.sequence)
}

// One expiring watchdog per request releases a pending refresh even if its callback is lost.
def expireDeviceRefresh(data) {
    if (!data?.cid || getRefreshControl(data.cid).inFlight != data.sequence) return
    completeDeviceRefresh(data.cid, data.sequence, false)
}

def completeDeviceRefresh(cid, sequence, success) {
    def control = getRefreshControl(cid)
    // Ignore callbacks from a request whose guard already expired and was replaced.
    if (control.inFlight != sequence) return
    control.inFlight = null
    control.failures = success ? 0 : Math.min(6, (control.failures ?: 0) + 1)
    control.retryAfter = success ? 0L : now() + refreshBackoffMillis(control.failures)
    def followUp = control.followUp == true
    control.followUp = false
    saveRefreshControl(cid, control)
    if (followUp) scheduleDeviceRefresh(cid)
}

// API Communication
def getDeviceDetails(child, sequence) {
    def cid = child.deviceNetworkId
    logDebug "Getting details for device: ${cid}"

    if (!isTokenValid()) {
        logError "Cannot get device details - token expired or not authenticated"
        return
    }

    def deviceType = child.getDataValue("deviceType")
    def category = getDeviceCategory(deviceType)
    def cfg = getCategoryConfig(category)

    if (!cfg) {
        logError "No API configuration for category '${category}' (${deviceType})"
        return
    }

    // For bypassV2 endpoints the outer method is "bypassV2" and the real API method moves
    // into the payload; everything else sends the API method directly.
    def body = buildBaseBody([
        uuid: child.getDataValue("uuid"),
        cid: cid,
        configModule: child.getDataValue("configModule") ?: deviceType,
        deviceRegion: child.getDataValue("deviceRegion") ?: "US",
        method: cfg.bypassV2 ? "bypassV2" : cfg.statusMethod
    ])

    if (cfg.bypassV2) {
        body.payload = [
            data: cfg.statusPayload ?: [:],
            method: cfg.statusMethod,
            source: "APP"
        ]
    }

    def params = [
        uri: "${getApiUrl()}${cfg.statusEndpoint}",
        contentType: "application/json",
        timeout: HTTP_TIMEOUT_SECONDS,
        body: JsonOutput.toJson(body)
    ]

    logDebug "Device details request to: ${cfg.statusEndpoint} with method: ${cfg.statusMethod}"

    def control = getRefreshControl(cid)
    control.inFlight = sequence
    control.startedAt = now()
    control.lastStartedAt = control.startedAt
    saveRefreshControl(cid, control)
    try {
        asynchttpPost("handleDeviceDetailsResponse", params,
            [cid: cid, category: category, sequence: sequence])
        runInMillis(REFRESH_GUARD_MS, "expireDeviceRefresh",
            [overwrite: false, data: [cid: cid, sequence: sequence]])
    } catch (e) {
        logError "Error getting device details: ${e.message}"
        completeDeviceRefresh(cid, sequence, false)
    }
}

def handleDeviceDetailsResponse(resp, data) {
    if (!data?.cid || data.sequence == null) return
    if (getRefreshControl(data.cid).inFlight != data.sequence) return
    def success = false
    try {
        if (resp.status == 200) {
            def jsonData = new JsonSlurper().parseText(resp.data)
            logDebug "Device details for ${data.cid}: code=${jsonData?.code}, msg=${jsonData?.msg}"
            def innerCode = jsonData?.result instanceof Map ? jsonData.result.code : null
            if (jsonData?.code == 0 && jsonData?.result && (innerCode == null || innerCode == 0)) {
                def deviceData = jsonData.result.result ?: jsonData.result.data ?: jsonData.result
                updateChildDevice(data.cid, deviceData, data.category)
                success = true
            } else {
                logDebug "Failed to get device details for ${data.cid}: ${jsonData?.msg ?: 'code=' + jsonData?.code}"
            }
        } else {
            logDebug "Device details HTTP error for ${data.cid}: ${resp.status}"
        }
    } catch (e) {
        logDebug "Device details parse error for ${data.cid}: ${e.message}"
    } finally {
        completeDeviceRefresh(data.cid, data.sequence, success)
    }
}

def updateChildDevice(cid, data, category) {
    def child = getChildDevice(cid)
    if (!child) return

    switch(category) {
        case "purifier":
            updatePurifierDevice(child, data)
            break
        case "humidifier":
            updateHumidifierDevice(child, data)
            break
        case "bulb":
        case "dimmer":
            updateLightDevice(child, data)
            break
        case "outlet":
            updateOutletDevice(child, data)
            break
        case "fan":
            updateFanDevice(child, data)
            break
        case "switch":
            updateSwitchDevice(child, data)
            break
    }
}

def updatePurifierDevice(child, data) {
    def status = data.result ?: data

    // Power state
    def powerState = status.enabled != null ? toOnOff(status.enabled) == "on" : (status.deviceStatus == "on")
    child.sendEvent(name: "switch", value: powerState ? "on" : "off")

    // Fan speed. The driver owns the level -> ENUM/percent translation so a polled update
    // and a commanded one leave the same attributes in the same shape.
    def level = pick(status, ["level", "fan_level", "speed"], 0)
    child.publishSpeed(level)

    // Mode
    def mode = pick(status, ["mode"], "manual")
    child.sendEvent(name: "mode", value: mode)

    // Filter life - pick() rather than ?: so a spent (0%) filter doesn't read as 100%
    def filterLife = pick(status, ["filter_life", "filterLife"])
    if (filterLife != null) {
        child.sendEvent(name: "filterLife", value: filterLife, unit: "%")
    }

    def aqDevice = getChildDevice("${child.deviceNetworkId}-AQ")

    // Air quality
    def aq = pick(status, ["air_quality", "airQuality"])
    if (aq != null) {
        child.sendEvent(name: "airQuality", value: aq)
        aqDevice?.sendEvent(name: "airQuality", value: aq)
    }

    // PM2.5 - hand the raw readings to the sensor driver, which owns AQI classification
    def pm25 = pick(status, ["air_quality_value", "pm25"])
    def pm10 = pick(status, ["pm10"])
    if (pm25 != null) {
        child.sendEvent(name: "pm25", value: pm25, unit: "μg/m³")
        if (pm10 != null) child.sendEvent(name: "pm10", value: pm10, unit: "μg/m³")
        aqDevice?.updateAirQuality(pm25, pm10)
    }

    // Child lock
    def childLock = pick(status, ["child_lock", "childLock"])
    if (childLock != null) {
        child.sendEvent(name: "childLock", value: toOnOff(childLock))
    }

    // Display - screenStatus is the numeric form used by some models
    def display = pick(status, ["display", "screenStatus"])
    if (display != null) {
        child.sendEvent(name: "display", value: toOnOff(display))
    }

    logDebug "Updated purifier ${child.label}: power=${powerState}, level=${level}, mode=${mode}"
}

// VeSync reports booleans as true/false, 0/1 or "on"/"off" depending on model and field.
def toOnOff(value) {
    if (value instanceof Boolean) return value ? "on" : "off"
    if (value instanceof Number) return value.intValue() != 0 ? "on" : "off"
    return value?.toString()?.toLowerCase() in ["on", "true", "1"] ? "on" : "off"
}

def updateHumidifierDevice(child, data) {
    def status = data.result ?: data

    // Power state - models disagree on both the field name and the encoding
    def rawPower = pick(status, ["device_status", "deviceStatus", "powerSwitch", "enabled"])
    def powerState = rawPower != null && toOnOff(rawPower) == "on"
    child.sendEvent(name: "switch", value: powerState ? "on" : "off")

    // Current humidity
    def humidity = pick(status, ["humidity"], 0)
    child.sendEvent(name: "humidity", value: humidity, unit: "%")

    // Temperature (Superior 6000S and other models with temperature sensors)
    def temperature = pick(status, ["humidity_temperature", "temperature"])
    if (temperature != null) {
        def scale = location.temperatureScale ?: "F"
        child.sendEvent(name: "temperature", value: temperature, unit: "°${scale}")
    }

    def targetHumidity = pick(status, ["target_humidity", "targetHumidity", "auto_target_humidity"])
    if (targetHumidity == null) {
        targetHumidity = status.configuration?.auto_target_humidity
    }
    if (targetHumidity != null) {
        child.sendEvent(name: "targetHumidity", value: targetHumidity, unit: "%")
    }

    // Mist level / virtual level (1-9 for Superior 6000S). pick() so level 0 stays 0.
    def mistLevel = pick(status, ["mist_virtual_level", "mist_level", "mistLevel", "level"], 0)
    child.sendEvent(name: "mistLevel", value: mistLevel)

    // Mode - Superior 6000S uses mist_mode / workMode
    def mode = pick(status, ["mist_mode", "mode", "workMode"], "manual")
    child.sendEvent(name: "mode", value: mode)

    // Water level / tank status
    def waterLack = pick(status, ["water_lacks", "waterLack", "water_tank_lifted"], false)
    child.sendEvent(name: "waterLevel", value: toOnOff(waterLack) == "on" ? "low" : "ok")

    // Night light
    def nightLight = pick(status, ["night_light_brightness"])
    if (nightLight != null) {
        child.sendEvent(name: "nightLightBrightness", value: nightLight)
    }

    // Drying mode (Superior 6000S specific)
    def drying = pick(status, ["drying_mode_state"])
    if (drying != null) {
        child.sendEvent(name: "dryingMode", value: toOnOff(drying))
    }

    logDebug "Updated humidifier ${child.label}: power=${powerState}, humidity=${humidity}%, target=${targetHumidity}%, mode=${mode}"
}

def updateLightDevice(child, data) {
    def status = data

    // Power state
    def powerState = status.deviceStatus == "on"
    child.sendEvent(name: "switch", value: powerState ? "on" : "off")

    // Brightness
    if (status.brightness != null) {
        child.sendEvent(name: "level", value: status.brightness, unit: "%")
    }

    // Color temperature
    if (status.colorTemp != null) {
        // Convert from device range (0-100) to Kelvin
        def kelvin = Math.round(2700 + (status.colorTemp / 100.0) * (6500 - 2700))
        child.sendEvent(name: "colorTemperature", value: kelvin, unit: "K")
    }

    // RGB Color
    if (status.hue != null && status.saturation != null) {
        child.sendEvent(name: "hue", value: status.hue)
        child.sendEvent(name: "saturation", value: status.saturation)
        child.sendEvent(name: "colorMode", value: "RGB")
    } else if (status.colorTemp != null) {
        child.sendEvent(name: "colorMode", value: "CT")
    }

    logDebug "Updated light ${child.label}: power=${powerState}, brightness=${status.brightness}"
}

def updateOutletDevice(child, data) {
    def status = data

    // Power state
    def powerState = status.deviceStatus == "on"
    child.sendEvent(name: "switch", value: powerState ? "on" : "off")

    // The driver owns power-metric handling: it derives amperage and outletInUse and
    // applies the resetEnergy offset, none of which happens if we sendEvent directly.
    child.updatePowerMetrics(pick(status, ["power"]),
                             pick(status, ["voltage"]),
                             pick(status, ["energy"]))

    logDebug "Updated outlet ${child.label}: power=${powerState}, watts=${status.power}"
}

def updateFanDevice(child, data) {
    def status = data.result ?: data

    // Power state
    def powerState = status.enabled != null ? toOnOff(status.enabled) == "on" : (status.deviceStatus == "on")
    child.sendEvent(name: "switch", value: powerState ? "on" : "off")

    // Speed - driver translates the raw level into the FanControl ENUM and percentage
    def level = pick(status, ["level", "fan_level"], 0)
    child.publishSpeed(level)

    // Mode
    def mode = pick(status, ["mode"], "normal")
    child.sendEvent(name: "mode", value: mode)

    // Oscillation
    def oscillation = pick(status, ["oscillation_state", "oscillationState"], false)
    child.sendEvent(name: "oscillation", value: toOnOff(oscillation))

    logDebug "Updated fan ${child.label}: power=${powerState}, level=${level}, mode=${mode}"
}

def updateSwitchDevice(child, data) {
    def status = data

    // Power state
    def powerState = status.deviceStatus == "on"
    child.sendEvent(name: "switch", value: powerState ? "on" : "off")

    logDebug "Updated switch ${child.label}: power=${powerState}"
}

// Device Control Methods (called by child devices)
def childOn(cid) {
    sendDeviceCommand(cid, "turnOn", [:])
}

def childOff(cid) {
    sendDeviceCommand(cid, "turnOff", [:])
}

def childSetSpeed(cid, level) {
    sendDeviceCommand(cid, "setSpeed", [level: level])
}

def childSetMode(cid, mode) {
    sendDeviceCommand(cid, "setMode", [mode: mode])
}

def childSetBrightness(cid, brightness) {
    sendDeviceCommand(cid, "setBrightness", [brightness: brightness])
}

def childSetColorTemperature(cid, kelvin) {
    // Convert Kelvin to device range (0-100)
    def deviceTemp = Math.round(((kelvin - 2700) / (6500 - 2700)) * 100)
    deviceTemp = Math.max(0, Math.min(100, deviceTemp))
    sendDeviceCommand(cid, "setColorTemperature", [colorTemp: deviceTemp])
}

def childSetColor(cid, hue, saturation, level) {
    sendDeviceCommand(cid, "setColor", [hue: hue, saturation: saturation, brightness: level])
}

def childSetTargetHumidity(cid, humidity) {
    sendDeviceCommand(cid, "setTargetHumidity", [humidity: humidity])
}

def childSetMistLevel(cid, level) {
    sendDeviceCommand(cid, "setMistLevel", [level: level])
}

def childSetOscillation(cid, state) {
    sendDeviceCommand(cid, "setOscillation", [state: state])
}

def childSetChildLock(cid, state) {
    sendDeviceCommand(cid, "setChildLock", [state: state])
}

def childSetDisplay(cid, state) {
    sendDeviceCommand(cid, "setDisplay", [state: state])
}

def childSetDryingMode(cid, enabled) {
    sendDeviceCommand(cid, "setDryingMode", [enabled: enabled])
}

def childSetNightLight(cid, brightness) {
    sendDeviceCommand(cid, "setNightLight", [brightness: brightness])
}

def childSetNightLightMode(cid, mode) {
    sendDeviceCommand(cid, "setNightLightMode", [mode: mode])
}

def childSetAutoStop(cid, enabled) {
    sendDeviceCommand(cid, "setAutoStop", [enabled: enabled])
}

def childSetTimer(cid, hours) {
    sendDeviceCommand(cid, "setTimer", [hours: hours])
}

def childSetIndicatorLight(cid, enabled) {
    sendDeviceCommand(cid, "setIndicatorLight", [enabled: enabled])
}

def childSetIndicatorColor(cid, red, green, blue) {
    sendDeviceCommand(cid, "setIndicatorColor", [red: red, green: green, blue: blue])
}

def sendDeviceCommand(cid, command, cmdParams) {
    if (!isTokenValid()) {
        logError "Cannot send command - token expired or not authenticated"
        authenticate()
        return false
    }

    def child = getChildDevice(cid)
    if (!child) {
        logError "Device not found: ${cid}"
        return false
    }

    def deviceType = child.getDataValue("deviceType")
    def category = getDeviceCategory(deviceType)
    def cfg = getCategoryConfig(category)

    if (!cfg) {
        logError "No API configuration for category '${category}' (${deviceType})"
        return false
    }

    // Refuse to send rather than POSTing method:"" - an unmapped command used to fail
    // silently, which is how six driver commands shipped broken.
    def commandPayload = buildCommandPayload(command, cmdParams, deviceType, category)
    if (!commandPayload?.method) {
        logError "Command '${command}' is not supported for ${child.label} (${deviceType}, category ${category}) - nothing sent"
        return false
    }

    def body = buildBaseBody([
        uuid: child.getDataValue("uuid"),
        cid: cid,
        configModule: child.getDataValue("configModule") ?: deviceType,
        deviceRegion: child.getDataValue("deviceRegion") ?: "US",
        // Commands send the inner method name at the outer level, unlike status reads which
        // send "bypassV2" there. The asymmetry looks wrong but is what the verified
        // hardware accepts today, so it is preserved deliberately.
        method: commandPayload.method
    ])

    // For bypassV2 endpoints, wrap payload properly
    if (cfg.bypassV2) {
        body.payload = [
            data: commandPayload.data,
            method: commandPayload.method,
            source: "APP"
        ]
    } else {
        body.payload = commandPayload.data
    }

    def httpParams = [
        uri: "${getApiUrl()}${commandPayload.endpoint ?: cfg.commandEndpoint}",
        contentType: "application/json",
        timeout: HTTP_TIMEOUT_SECONDS,
        body: JsonOutput.toJson(body)
    ]

    logDebug "Sending command ${command} to ${child.label}: method=${commandPayload.method}, data=${commandPayload.data}"

    def callbackData = [cid: cid, command: command, childLabel: child.label, category: category]

    try {
        asynchttpPost("handleCommandResponse", httpParams, callbackData)
        return true
    } catch (e) {
        logError "Error sending command: ${e.message}"
        return false
    }
}

def handleCommandResponse(resp, data) {
    def failure = null

    if (resp.status != 200) {
        failure = "HTTP ${resp.status}"
    } else {
        try {
            def jsonData = new JsonSlurper().parseText(resp.data)
            def innerCode = jsonData?.result instanceof Map ? jsonData.result.code : null
            if (jsonData?.code == 0 && (innerCode == null || innerCode == 0)) {
                logInfo "Command ${data.command} sent to ${data.childLabel}"
            } else {
                failure = (jsonData?.result instanceof Map ? jsonData.result.msg : null) ?:
                    jsonData?.msg ?: "code=${innerCode != null ? innerCode : jsonData?.code}"
            }
        } catch (e) {
            failure = "response parse error: ${e.message}"
        }
    }

    if (failure) {
        logError "Command ${data.command} failed for ${data.childLabel}: ${failure}"
        if (data.command == "setBrightness" && data.category in ["bulb", "dimmer"]) {
            getChildDevice(data.cid)?.stopLevelChange()
        }
    }

    // Resync either way. Drivers update their attributes optimistically, so on failure this
    // is what pulls the UI back to reality instead of leaving it wrong until the next poll.
    scheduleDeviceRefresh(data.cid, failure ? 1000L : 2000L)
}

// Returns [method:, data:, endpoint:(optional override)] or null when the command is not
// supported for this device. Callers must treat null as "do not send".
def buildCommandPayload(command, params, deviceType, category) {
    def method = ""
    def data = [:]
    def endpoint = null

    // Field-naming differences (Superior 6000S and friends) come from the quirks table
    // rather than an isXxx boolean threaded through every branch.
    def q = getQuirks(deviceType)
    def bypass = category in ["purifier", "humidifier", "fan"]

    logDebug "buildCommandPayload: command=${command}, deviceType=${deviceType}, category=${category}"

    switch(command) {
        case "turnOn":
        case "turnOff":
            def on = (command == "turnOn")
            if (bypass) {
                method = "setSwitch"
                data = q.numericPower ? [powerSwitch: on ? 1 : 0, id: 0] : [enabled: on, id: 0]
            } else {
                method = "devicestatus"
                data = [status: on ? "on" : "off"]
            }
            break

        case "setSpeed":
            method = category == "purifier" ? "setLevel" : "setFanSpeed"
            data = [level: params.level, id: 0, type: "wind"]
            break

        case "setMode":
        case "setAutoMode":
        case "setManualMode":
            def mode = params?.mode
            if (command == "setAutoMode") mode = "auto"
            if (command == "setManualMode") mode = "manual"

            if (category == "humidifier") {
                method = "setHumidityMode"
                // Some models name auto differently (Superior 6000S: "autoPro")
                def value = (mode == "auto") ? q.autoMode : mode
                data = [(q.modeKey): value, id: 0]
            } else {
                method = "setPurifierMode"
                data = [mode: mode]
            }
            break

        case "setDryingMode":
            // Superior 6000S specific - enable/disable drying mode
            method = "setDryingMode"
            data = [drying_mode_enabled: params.enabled, id: 0]
            break

        case "setBrightness":
            method = "devicestatus"
            data = [brightness: params.brightness, status: "on"]
            break

        case "setColorTemperature":
            method = "devicestatus"
            data = [colorTemp: params.colorTemp, status: "on"]
            break

        case "setColor":
            method = "devicestatus"
            data = [
                hue: params.hue,
                saturation: params.saturation,
                brightness: params.brightness ?: 100,
                status: "on",
                colorMode: "hsv"
            ]
            break

        case "setTargetHumidity":
            method = "setTargetHumidity"
            data = [(q.targetHumidityKey): params.humidity, id: 0]
            break

        case "setMistLevel":
            method = "setVirtualLevel"
            data = [level: params.level, id: 0, type: "mist"]
            break

        case "setOscillation":
            method = "setOscillationSwitch"
            data = [enabled: asBool(pick(params, ["enabled", "state"]))]
            break

        case "setChildLock":
            method = "setChildLock"
            data = [child_lock: asBool(pick(params, ["enabled", "state"]))]
            break

        case "setDisplay":
            method = "setDisplay"
            data = [state: asBool(pick(params, ["enabled", "state"]))]
            break

        // ---------------------------------------------------------------------------
        // The following commands were exposed by drivers but had no payload here, so
        // they silently POSTed method:"" and did nothing. Payload shapes below follow
        // the conventions of the surrounding bypassV2 / v1 calls.
        //
        // UNVERIFIED - no hardware. None of these run on a verified model (Core200S-P,
        // Core400S-P, Superior6000S). Confirm against pyvesync/tsvesync before relying
        // on them; sendDeviceCommand now logs an explicit error if a device rejects one.
        // ---------------------------------------------------------------------------

        case "setNightLight":
            // UNVERIFIED - humidifiers with a night light (Classic/Dual/LV600S/OasisMist)
            method = "setNightLightBrightness"
            data = [night_light_brightness: params.brightness, id: 0]
            break

        case "setAutoStop":
            // UNVERIFIED - humidifiers that stop at target humidity
            method = "setAutomaticStop"
            data = [enabled: asBool(pick(params, ["enabled", "state"])), id: 0]
            break

        case "setTimer":
            // UNVERIFIED - tower fans (LTF-F422S). Hours converted to seconds.
            method = "setTimer"
            data = [total: (params.hours ?: 0) * 3600, action: "off", id: 0]
            break

        case "setNightLightMode":
            // UNVERIFIED - outlets with an RGB night light (ESO15-TB, ESW15-USA)
            method = "outletNightLightCtl"
            data = [mode: params.mode]
            break

        case "setIndicatorLight":
            // UNVERIFIED - ESWD16 dimmer. Uses its own endpoint, not the SmartBulb one.
            method = "indicatorLightStatus"
            endpoint = "/dimmer/v1/device/indicatorlightstatus"
            data = [status: asBool(pick(params, ["enabled", "state"])) ? "on" : "off"]
            break

        case "setIndicatorColor":
            // UNVERIFIED - ESWD16 dimmer RGB indicator ring
            method = "devicergbstatus"
            endpoint = "/dimmer/v1/device/devicergbstatus"
            data = [status: "on", rgbValue: [red: params.red, green: params.green, blue: params.blue]]
            break

        default:
            logDebug "buildCommandPayload: no mapping for command '${command}'"
            return null
    }

    return [method: method, data: data, endpoint: endpoint]
}

// Driver commands arrive as "on"/"off" strings or booleans depending on the call site.
def asBool(value) {
    if (value instanceof Boolean) return value
    if (value instanceof Number) return value.intValue() != 0
    return value?.toString()?.toLowerCase() in ["on", "true", "1"]
}

// Logging
def logDebug(msg) {
    if (settings.debugLogging) {
        log.debug "[VeSync] ${msg}"
    }
}

def logInfo(msg) {
    if (settings.descriptionLogging != false) {
        log.info "[VeSync] ${msg}"
    }
}

def logError(msg) {
    log.error "[VeSync] ${msg}"
}
