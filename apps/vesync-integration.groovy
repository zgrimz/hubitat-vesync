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

@Field static final String VERSION = "1.0.0"
@Field static final String NAMESPACE = "vesync"

// API Endpoints
@Field static final String API_BASE_URL_US = "https://smartapi.vesync.com"
@Field static final String API_BASE_URL_EU = "https://smartapi.vesync.eu"

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
            section("Status") {
                paragraph "Connected to VeSync as: ${settings.username}"
                if (tokenValid) {
                    paragraph "Token expires: ${state.tokenExpiry ? new Date(state.tokenExpiry).format("yyyy-MM-dd HH:mm:ss") : 'Unknown'}"
                } else {
                    paragraph "<b style='color:red'>TOKEN EXPIRED</b> - Token expired on: ${state.tokenExpiry ? new Date(state.tokenExpiry).format("yyyy-MM-dd HH:mm:ss") : 'Unknown'}. Click 'Re-authenticate' below."
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
                         "outlet": "Outlets", "fan": "Fans", "switch": "Switches"]
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

    if (isTokenValid()) {
        scheduleTokenRefresh()
        schedulePolling()
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
            state.tokenExpiry = null
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
    def hashedPassword = hashPassword(settings.password)

    def body = [
        email: settings.username,
        password: hashedPassword,
        appVersion: "2.8.6",
        phoneBrand: "SM N9005",
        phoneOS: "Android",
        acceptLanguage: "en",
        timeZone: "America/New_York",
        method: "login",
        token: "",
        accountID: "",
        devToken: "",
        userType: "1",
        traceId: now().toString()
    ]

    def params = [
        uri: "${apiUrl}/cloud/v1/user/login",
        contentType: "application/json",
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
            // Use atomicState for token expiry to ensure consistency across async callbacks
            def newExpiry = now() + (30L * 24L * 60L * 60L * 1000L) // 30 days - use Long to avoid integer overflow
            atomicState.tokenExpiry = newExpiry
            state.tokenExpiry = newExpiry
            state.token = jsonData.result.token
            state.accountId = jsonData.result.accountID
            state.authMessage = "Authentication successful!"
            logInfo "Successfully authenticated with VeSync - new token expires: ${new Date(newExpiry)}"

            // Schedule tasks with a short delay to ensure state is persisted
            runIn(2, "scheduleTokenRefresh")
            runIn(3, "schedulePollingDirect")
        } else {
            state.authMessage = "Authentication failed: ${jsonData?.msg ?: 'Unknown error (code: ' + jsonData?.code + ')'}"
            logError "Authentication failed: ${jsonData?.msg ?: jsonData}"
        }
    } else {
        state.authMessage = "Authentication failed: HTTP ${resp.status}"
        logError "Authentication failed: status=${resp.status}"
    }
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
    // Prefer atomicState.tokenExpiry as it's more consistent across async callbacks
    def expiry = atomicState.tokenExpiry ?: state.tokenExpiry
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
    def refreshTime = state.tokenExpiry ? state.tokenExpiry - (5L * 24L * 60L * 60L * 1000L) : now() + (25L * 24L * 60L * 60L * 1000L)
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

    schedulePollingDirect()
}

// Called directly after auth success - skips token validity check since we just authenticated
def schedulePollingDirect() {
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

    def body = [
        acceptLanguage: "en",
        appVersion: "2.8.6",
        phoneBrand: "SM N9005",
        phoneOS: "Android",
        timeZone: "America/New_York",
        accountID: state.accountId,
        token: state.token,
        method: "devices",
        pageNo: 1,
        pageSize: 100,
        traceId: now().toString()
    ]

    def params = [
        uri: "${apiUrl}/cloud/v1/deviceManaged/devices",
        contentType: "application/json",
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
    if (deviceType.contains("ESO") || deviceType.contains("ESW") && !deviceType.contains("ESWL")) {
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

def getDriverName(category) {
    switch(category) {
        case "purifier": return "VeSync Air Purifier"
        case "humidifier": return "VeSync Humidifier"
        case "bulb": return "VeSync Light"
        case "dimmer": return "VeSync Dimmer"
        case "outlet": return "VeSync Outlet"
        case "fan": return "VeSync Fan"
        case "switch": return "VeSync Switch"
        default: return null
    }
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

        logInfo "Created device: ${deviceInfo.deviceName} (${driverName})"

        // Initial refresh
        runIn(2, "refreshChildDevice", [data: [cid: deviceInfo.cid]])

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

    getChildDevices().each { child ->
        // Skip AQ sensor devices - they get updated with their parent
        if (!child.deviceNetworkId.endsWith("-AQ")) {
            refreshChildDevice([cid: child.deviceNetworkId])
        }
    }
}

def refreshChildDevice(data) {
    def cid = data.cid
    def child = getChildDevice(cid)

    if (!child) {
        logDebug "Child device not found: ${cid}"
        return
    }

    def deviceType = child.getDataValue("deviceType")
    def uuid = child.getDataValue("uuid")
    def configModule = child.getDataValue("configModule")

    getDeviceDetails(cid, deviceType, uuid, configModule)
}

// API Communication
def getDeviceDetails(cid, deviceType, uuid, configModule) {
    logDebug "Getting details for device: ${cid}"

    if (!isTokenValid()) {
        logError "Cannot get device details - token expired or not authenticated"
        return
    }

    def apiUrl = getApiUrl()
    def category = getDeviceCategory(deviceType)

    // Determine API method based on device type
    def apiMethod = getApiMethod(deviceType, category)
    def apiEndpoint = getApiEndpoint(category, apiMethod)

    // For bypassV2 endpoints, the outer method is "bypassV2", the actual API method goes in payload
    def outerMethod = (category in ["purifier", "humidifier", "fan"]) ? "bypassV2" : apiMethod

    def body = [
        acceptLanguage: "en",
        appVersion: "2.8.6",
        phoneBrand: "SM N9005",
        phoneOS: "Android",
        timeZone: "America/New_York",
        accountID: state.accountId,
        token: state.token,
        uuid: uuid,
        cid: cid,
        configModule: configModule ?: deviceType,
        deviceRegion: "US",
        method: outerMethod,
        traceId: now().toString()
    ]

    // For bypassV2 endpoints (purifier, humidifier, fan), add payload structure
    if (category in ["purifier", "humidifier", "fan"]) {
        // Purifiers need type: "air", id: 0 in the data payload
        def payloadData = (category == "purifier") ? [type: "air", id: 0] : [:]
        body.payload = [
            data: payloadData,
            method: apiMethod,
            source: "APP"
        ]
    }

    def params = [
        uri: "${apiUrl}${apiEndpoint}",
        contentType: "application/json",
        body: JsonOutput.toJson(body)
    ]

    logDebug "Device details request to: ${apiEndpoint} with method: ${apiMethod}"

    def callbackData = [cid: cid, category: category]

    try {
        asynchttpPost("handleDeviceDetailsResponse", params, callbackData)
    } catch (e) {
        logError "Error getting device details: ${e.message}"
    }
}

def handleDeviceDetailsResponse(resp, data) {
    if (resp.status == 200) {
        try {
            def jsonData = new JsonSlurper().parseText(resp.data)
            logDebug "Device details response for ${data.cid}: code=${jsonData?.code}, hasResult=${jsonData?.result != null}"
            // Debug: log the full response structure
            logDebug "Device details TOP LEVEL keys: ${jsonData?.keySet()}"
            logDebug "Device details TOP LEVEL msg: ${jsonData?.msg}"
            logDebug "Device details result keys: ${jsonData?.result?.keySet()}"
            logDebug "Device details result.code: ${jsonData?.result?.code}, result.msg: ${jsonData?.result?.msg}"
            logDebug "Device details result.result keys: ${jsonData?.result?.result?.keySet()}"
            // Check for other common locations
            logDebug "Device details result.data keys: ${jsonData?.result?.data?.keySet()}"
            logDebug "Device details data keys: ${jsonData?.data?.keySet()}"
            if (jsonData?.code == 0 && jsonData?.result) {
                // For bypassV2 endpoints, data might be nested in result.result or result.data
                def deviceData = jsonData.result.result ?: jsonData.result.data ?: jsonData.result
                logDebug "Device data keys being used: ${deviceData?.keySet()}"
                updateChildDevice(data.cid, deviceData, data.category)
            } else {
                logDebug "Failed to get device details for ${data.cid}: ${jsonData?.msg ?: 'code=' + jsonData?.code}"
            }
        } catch (e) {
            logDebug "Device details parse error for ${data.cid}: ${e.message}"
        }
    } else {
        logDebug "Device details HTTP error for ${data.cid}: ${resp.status}"
    }
}

def getApiMethod(deviceType, category) {
    switch(category) {
        case "purifier":
            // All purifiers use getPurifierStatus with bypassV2 endpoint
            return "getPurifierStatus"
        case "humidifier":
            return "getHumidifierStatus"
        case "bulb":
        case "dimmer":
            return "getLightStatus"
        case "outlet":
            return "getOutletStatus"
        case "fan":
            return "getTowerFanStatus"
        case "switch":
            return "getSwitchStatus"
        default:
            return "devicestatus"
    }
}

def getApiEndpoint(category, method) {
    switch(category) {
        case "purifier":
            return "/cloud/v2/deviceManaged/bypassV2"
        case "humidifier":
            return "/cloud/v2/deviceManaged/bypassV2"
        case "bulb":
        case "dimmer":
            return "/SmartBulb/v1/device/devicedetail"
        case "outlet":
            return "/v1/device/${method}"
        case "fan":
            return "/cloud/v2/deviceManaged/bypassV2"
        case "switch":
            return "/inwallswitch/v1/device/devicedetail"
        default:
            return "/cloud/v1/deviceManaged/deviceDetail"
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

    // Log raw status for debugging - log all keys and full data
    logDebug "Purifier data keys: ${data?.keySet()}"
    logDebug "Purifier status keys: ${status?.keySet()}"
    logDebug "Purifier raw status: enabled=${status.enabled}, deviceStatus=${status.deviceStatus}, device_status=${status.device_status}, powerSwitch=${status.powerSwitch}, mode=${status.mode}, fan_level=${status.fan_level}, level=${status.level}, speed=${status.speed}"

    // Power state
    def powerState = status.enabled != null ? status.enabled : (status.deviceStatus == "on")
    child.sendEvent(name: "switch", value: powerState ? "on" : "off")

    // Fan speed
    def speed = status.level ?: status.fan_level ?: status.speed ?: 0
    child.sendEvent(name: "speed", value: speed)

    // Mode
    def mode = status.mode ?: "manual"
    child.sendEvent(name: "mode", value: mode)

    // Filter life
    def filterLife = status.filter_life ?: status.filterLife ?: 100
    child.sendEvent(name: "filterLife", value: filterLife, unit: "%")

    // Air quality
    if (status.air_quality != null || status.airQuality != null) {
        def aq = status.air_quality ?: status.airQuality
        child.sendEvent(name: "airQuality", value: aq)

        // Update AQ sensor device if exists
        def aqDevice = getChildDevice("${child.deviceNetworkId}-AQ")
        if (aqDevice) {
            aqDevice.sendEvent(name: "airQuality", value: aq)
        }
    }

    // PM2.5
    if (status.air_quality_value != null || status.pm25 != null) {
        def pm25 = status.air_quality_value ?: status.pm25 ?: 0
        child.sendEvent(name: "pm25", value: pm25, unit: "μg/m³")

        def aqDevice = getChildDevice("${child.deviceNetworkId}-AQ")
        if (aqDevice) {
            aqDevice.sendEvent(name: "pm25", value: pm25, unit: "μg/m³")
            aqDevice.sendEvent(name: "airQualityIndex", value: calculateAQI(pm25))
        }
    }

    // Child lock
    if (status.child_lock != null || status.childLock != null) {
        def childLock = status.child_lock ?: status.childLock
        child.sendEvent(name: "childLock", value: childLock ? "on" : "off")
    }

    // Display
    if (status.display != null || status.screenStatus != null) {
        def display = status.display ?: (status.screenStatus == 1)
        child.sendEvent(name: "display", value: display ? "on" : "off")
    }

    logDebug "Updated purifier ${child.label}: power=${powerState}, speed=${speed}, mode=${mode}"
}

def updateHumidifierDevice(child, data) {
    def status = data.result ?: data

    // Log raw status for debugging
    logDebug "Humidifier raw status: device_status=${status.device_status}, deviceStatus=${status.deviceStatus}, powerSwitch=${status.powerSwitch}, enabled=${status.enabled}, mode=${status.mode}, mist_mode=${status.mist_mode}, workMode=${status.workMode}"

    // Power state - Superior 6000S uses device_status field
    def powerState = false
    if (status.device_status != null) {
        powerState = status.device_status == "on"
    } else if (status.deviceStatus != null) {
        powerState = status.deviceStatus == "on"
    } else if (status.powerSwitch != null) {
        powerState = status.powerSwitch == 1 || status.powerSwitch == true
    } else if (status.enabled != null) {
        powerState = status.enabled
    }
    child.sendEvent(name: "switch", value: powerState ? "on" : "off")

    // Current humidity
    def humidity = status.humidity ?: 0
    child.sendEvent(name: "humidity", value: humidity, unit: "%")

    // Temperature (Superior 6000S and other models with temperature sensors)
    def temperature = status.humidity_temperature ?: status.temperature ?: null
    if (temperature != null) {
        child.sendEvent(name: "temperature", value: temperature, unit: "°F")
        logDebug "Humidifier temperature: ${temperature}°F"
    }

    // Target humidity - log all possible fields for debugging
    logDebug "Humidifier target humidity fields: target_humidity=${status.target_humidity}, targetHumidity=${status.targetHumidity}, auto_target_humidity=${status.auto_target_humidity}, configuration=${status.configuration}"
    def targetHumidity = status.target_humidity ?: status.targetHumidity ?: status.auto_target_humidity ?: status.configuration?.auto_target_humidity ?: 50
    child.sendEvent(name: "targetHumidity", value: targetHumidity, unit: "%")

    // Mist level / virtual level (1-9 for Superior 6000S)
    def mistLevel = status.mist_virtual_level ?: status.mist_level ?: status.mistLevel ?: status.level ?: 0
    child.sendEvent(name: "mistLevel", value: mistLevel)

    // Mode - Superior 6000S uses mist_mode field
    def mode = status.mist_mode ?: status.mode ?: status.workMode ?: "manual"
    child.sendEvent(name: "mode", value: mode)

    // Water level / tank status
    def waterLack = status.water_lacks ?: status.waterLack ?: status.water_tank_lifted ?: false
    child.sendEvent(name: "waterLevel", value: waterLack ? "low" : "ok")

    // Night light
    if (status.night_light_brightness != null) {
        child.sendEvent(name: "nightLightBrightness", value: status.night_light_brightness)
    }

    // Drying mode (Superior 6000S specific)
    if (status.drying_mode_state != null) {
        child.sendEvent(name: "dryingMode", value: status.drying_mode_state)
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

    // Power monitoring
    if (status.power != null) {
        child.sendEvent(name: "power", value: status.power, unit: "W")
    }

    if (status.voltage != null) {
        child.sendEvent(name: "voltage", value: status.voltage, unit: "V")
    }

    if (status.energy != null) {
        child.sendEvent(name: "energy", value: status.energy, unit: "kWh")
    }

    logDebug "Updated outlet ${child.label}: power=${powerState}, watts=${status.power}"
}

def updateFanDevice(child, data) {
    def status = data.result ?: data

    // Power state
    def powerState = status.enabled != null ? status.enabled : (status.deviceStatus == "on")
    child.sendEvent(name: "switch", value: powerState ? "on" : "off")

    // Speed
    def speed = status.level ?: status.fan_level ?: 0
    def maxSpeed = getMaxFanSpeed(child.getDataValue("deviceType"))
    def speedPercent = Math.round((speed / maxSpeed) * 100)
    child.sendEvent(name: "speed", value: speedPercent, unit: "%")
    child.sendEvent(name: "speedLevel", value: speed)

    // Mode
    def mode = status.mode ?: "normal"
    child.sendEvent(name: "mode", value: mode)

    // Oscillation
    def oscillation = status.oscillation_state ?: status.oscillationState ?: false
    child.sendEvent(name: "oscillation", value: oscillation ? "on" : "off")

    logDebug "Updated fan ${child.label}: power=${powerState}, speed=${speed}"
}

def updateSwitchDevice(child, data) {
    def status = data

    // Power state
    def powerState = status.deviceStatus == "on"
    child.sendEvent(name: "switch", value: powerState ? "on" : "off")

    logDebug "Updated switch ${child.label}: power=${powerState}"
}

def getMaxFanSpeed(deviceType) {
    if (deviceType?.startsWith("LTF-F422S")) return 12
    if (deviceType?.startsWith("Core200S")) return 3
    if (deviceType?.startsWith("Core300S")) return 3
    if (deviceType?.startsWith("Core400S")) return 4
    if (deviceType?.startsWith("Core600S")) return 4
    return 4
}

def calculateAQI(pm25) {
    if (pm25 <= 12) return 1  // Excellent
    if (pm25 <= 35) return 2  // Good
    if (pm25 <= 55) return 3  // Fair
    if (pm25 <= 150) return 4 // Poor
    return 5                   // Very Poor
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
    def uuid = child.getDataValue("uuid")
    def configModule = child.getDataValue("configModule")
    def category = getDeviceCategory(deviceType)

    def apiUrl = getApiUrl()
    def endpoint = getCommandEndpoint(category)
    def commandPayload = buildCommandPayload(command, cmdParams, deviceType, category)

    def body = [
        acceptLanguage: "en",
        appVersion: "2.8.6",
        phoneBrand: "SM N9005",
        phoneOS: "Android",
        timeZone: "America/New_York",
        accountID: state.accountId,
        token: state.token,
        uuid: uuid,
        cid: cid,
        configModule: configModule ?: deviceType,
        deviceRegion: "US",
        method: commandPayload.method,
        traceId: now().toString()
    ]

    // For bypassV2 endpoints, wrap payload properly
    if (category in ["purifier", "humidifier", "fan"]) {
        body.payload = [
            data: commandPayload.data,
            method: commandPayload.method,
            source: "APP"
        ]
    } else {
        body.payload = commandPayload.data
    }

    def httpParams = [
        uri: "${apiUrl}${endpoint}",
        contentType: "application/json",
        body: JsonOutput.toJson(body)
    ]

    logDebug "Sending command ${command} to ${child.label}: method=${commandPayload.method}, data=${commandPayload.data}"

    def callbackData = [cid: cid, command: command, childLabel: child.label]

    try {
        asynchttpPost("handleCommandResponse", httpParams, callbackData)
        return true
    } catch (e) {
        logError "Error sending command: ${e.message}"
        return false
    }
}

def handleCommandResponse(resp, data) {
    if (resp.status == 200) {
        try {
            def jsonData = new JsonSlurper().parseText(resp.data)
            if (jsonData?.code == 0) {
                logInfo "Command ${data.command} sent to ${data.childLabel}"
                // Refresh device state after command
                runIn(2, "refreshChildDevice", [data: [cid: data.cid]])
            } else {
                logError "Command ${data.command} failed: ${jsonData?.msg ?: 'code=' + jsonData?.code}"
            }
        } catch (e) {
            logError "Command response parse error: ${e.message}"
        }
    } else {
        logError "Command ${data.command} failed: HTTP ${resp.status}"
    }
}

def getCommandEndpoint(category) {
    switch(category) {
        case "purifier":
        case "humidifier":
        case "fan":
            return "/cloud/v2/deviceManaged/bypassV2"
        case "bulb":
        case "dimmer":
            return "/SmartBulb/v1/device/devicestatus"
        case "outlet":
            return "/10a/v1/device/devicestatus"
        case "switch":
            return "/inwallswitch/v1/device/devicestatus"
        default:
            return "/cloud/v1/deviceManaged/bypass"
    }
}

def buildCommandPayload(command, params, deviceType, category) {
    def method = ""
    def data = [:]

    // Check if this is a Superior 6000S (LEH-S601S) which uses powerSwitch: 0/1 instead of enabled: true/false
    def deviceTypeUpper = deviceType?.toUpperCase() ?: ""
    def isSuperior6000S = deviceTypeUpper.contains("LEH-S601S")

    logDebug "buildCommandPayload: command=${command}, deviceType=${deviceType}, category=${category}, isSuperior6000S=${isSuperior6000S}"

    switch(command) {
        case "turnOn":
            if (category == "purifier" || category == "humidifier" || category == "fan") {
                method = "setSwitch"
                if (isSuperior6000S) {
                    // Superior 6000S uses powerSwitch: 0/1
                    data = [powerSwitch: 1, id: 0]
                } else {
                    data = [enabled: true, id: 0]
                }
            } else {
                method = "devicestatus"
                data = [status: "on"]
            }
            break

        case "turnOff":
            if (category == "purifier" || category == "humidifier" || category == "fan") {
                method = "setSwitch"
                if (isSuperior6000S) {
                    // Superior 6000S uses powerSwitch: 0/1
                    data = [powerSwitch: 0, id: 0]
                } else {
                    data = [enabled: false, id: 0]
                }
            } else {
                method = "devicestatus"
                data = [status: "off"]
            }
            break

        case "setSpeed":
            method = category == "purifier" ? "setLevel" : "setFanSpeed"
            data = [level: params.level, id: 0, type: "wind"]
            break

        case "setMode":
            if (category == "humidifier") {
                // Superior 6000S uses setHumidityMode with workMode parameter
                method = "setHumidityMode"
                if (isSuperior6000S) {
                    // Superior 6000S uses workMode field and "autoPro" instead of "auto"
                    def modeValue = (params.mode == "auto") ? "autoPro" : params.mode
                    data = [workMode: modeValue, id: 0]
                } else {
                    data = [mode: params.mode, id: 0]
                }
            } else {
                method = "setPurifierMode"
                data = [mode: params.mode]
            }
            break

        case "setAutoMode":
            if (category == "humidifier") {
                method = "setHumidityMode"
                if (isSuperior6000S) {
                    // Superior 6000S uses "autoPro" instead of "auto"
                    data = [workMode: "autoPro", id: 0]
                } else {
                    data = [mode: "auto", id: 0]
                }
            } else {
                method = "setPurifierMode"
                data = [mode: "auto"]
            }
            break

        case "setManualMode":
            if (category == "humidifier") {
                method = "setHumidityMode"
                if (isSuperior6000S) {
                    data = [workMode: "manual", id: 0]
                } else {
                    data = [mode: "manual", id: 0]
                }
            } else {
                method = "setPurifierMode"
                data = [mode: "manual"]
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
            if (isSuperior6000S) {
                // Superior 6000S uses targetHumidity (camelCase) based on status response
                data = [targetHumidity: params.humidity, id: 0]
            } else {
                data = [target_humidity: params.humidity, id: 0]
            }
            break

        case "setMistLevel":
            method = "setVirtualLevel"
            data = [level: params.level, id: 0, type: "mist"]
            break

        case "setOscillation":
            method = "setOscillationSwitch"
            data = [enabled: params.state == "on" || params.state == true]
            break

        case "setChildLock":
            method = "setChildLock"
            data = [child_lock: params.state == "on" || params.state == true]
            break

        case "setDisplay":
            method = "setDisplay"
            data = [state: params.state == "on" || params.state == true]
            break
    }

    return [method: method, data: data]
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
