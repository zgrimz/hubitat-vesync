/**
 *  VeSync Air Purifier
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
 *  VeSync Air Purifier Driver
 *
 *  Supports: Core200S, Core300S, Core400S, Core600S, Vital100S, Vital200S, LAP-C/V/EL series, LV-PUR131S
 *
 */

import groovy.transform.Field

@Field static final String VERSION = "1.0.0"

metadata {
    definition(name: "VeSync Air Purifier", namespace: "vesync", author: "VeSync Hubitat Integration") {
        capability "Switch"
        capability "FanControl"
        capability "Refresh"
        capability "Actuator"
        capability "Sensor"

        // Custom attributes
        attribute "mode", "string"
        attribute "speed", "number"
        attribute "speedLevel", "number"
        attribute "filterLife", "number"
        attribute "airQuality", "string"
        attribute "airQualityIndex", "number"
        attribute "pm25", "number"
        attribute "pm10", "number"
        attribute "childLock", "string"
        attribute "display", "string"
        attribute "deviceStatus", "string"

        // Commands
        command "setSpeed", [[name: "Speed Level*", type: "NUMBER", description: "Fan speed level (1-4)"]]
        command "setMode", [[name: "Mode*", type: "ENUM", constraints: ["manual", "auto", "sleep", "pet", "turbo"]]]
        command "speedUp"
        command "speedDown"
        command "setChildLock", [[name: "State*", type: "ENUM", constraints: ["on", "off"]]]
        command "setDisplay", [[name: "State*", type: "ENUM", constraints: ["on", "off"]]]
        command "cycleSpeed"
    }

    preferences {
        input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: false
        input name: "txtEnable", type: "bool", title: "Enable description text logging", defaultValue: true
        input name: "maxSpeed", type: "number", title: "Maximum Speed Level", defaultValue: 4, range: "1..12"
    }
}

def installed() {
    log.info "VeSync Air Purifier installed"
    initialize()
}

def updated() {
    log.info "VeSync Air Purifier updated"
    initialize()
}

def initialize() {
    if (logEnable) runIn(1800, "logsOff")

    // Auto-detect max speed based on device type if not manually overridden
    def detectedMax = getMaxSpeedForDevice()
    if (detectedMax && (!settings.maxSpeed || settings.maxSpeed == 4)) {
        device.updateSetting("maxSpeed", [value: detectedMax, type: "number"])
        log.info "Auto-detected max speed: ${detectedMax} for device type: ${getDeviceType()}"
    }
}

// Get device type from stored data
def getDeviceType() {
    return device.getDataValue("deviceType") ?: ""
}

// Determine max speed based on device model
def getMaxSpeedForDevice() {
    def deviceType = getDeviceType().toUpperCase()

    // Core 200S series - 3 speeds (low, medium, high)
    if (deviceType.contains("CORE200S") || deviceType.contains("CORE200")) {
        return 3
    }
    // Core 300S series - 3 speeds
    if (deviceType.contains("CORE300S") || deviceType.contains("CORE300")) {
        return 3
    }
    // Core 400S series - 4 speeds
    if (deviceType.contains("CORE400S") || deviceType.contains("CORE400")) {
        return 4
    }
    // Core 600S series - 4 speeds
    if (deviceType.contains("CORE600S") || deviceType.contains("CORE600")) {
        return 4
    }
    // LV-PUR131S - 3 speeds
    if (deviceType.contains("LV-PUR131") || deviceType.contains("LV-RH131")) {
        return 3
    }
    // Vital series - 4 speeds
    if (deviceType.contains("VITAL")) {
        return 4
    }
    // LAP series - default to 4
    if (deviceType.contains("LAP-")) {
        return 4
    }

    // Default - use manual setting or 4
    return settings.maxSpeed ?: 4
}

def logsOff() {
    log.warn "Debug logging disabled"
    device.updateSetting("logEnable", [value: "false", type: "bool"])
}

// Switch Capability
def on() {
    logDebug "Turning on"
    parent.childOn(device.deviceNetworkId)
    sendEvent(name: "switch", value: "on")
}

def off() {
    logDebug "Turning off"
    parent.childOff(device.deviceNetworkId)
    sendEvent(name: "switch", value: "off")
}

// FanControl Capability
def setSpeed(speed) {
    def max = getMaxSpeedForDevice()

    if (speed instanceof String) {
        // Handle standard fan speed names
        switch(speed.toLowerCase()) {
            case "off":
                off()
                return
            case "on":
            case "auto":
                setMode("auto")
                return
            case "low":
                setSpeedLevel(1)
                return
            case "medium-low":
                // For 3-speed devices, medium-low = 2; for 4-speed, medium-low = 2
                setSpeedLevel(2)
                return
            case "medium":
                // For 3-speed devices, medium = 2; for 4-speed, medium = 2
                setSpeedLevel(Math.round(max / 2))
                return
            case "medium-high":
                // For 3-speed devices, medium-high = 2; for 4-speed, medium-high = 3
                setSpeedLevel(Math.round(max * 0.75))
                return
            case "high":
                setSpeedLevel(max)
                return
        }
    }

    // Numeric speed (percentage)
    def level = Math.round((speed / 100.0) * max)
    level = Math.max(1, Math.min(max, level))
    setSpeedLevel(level)
}

def setSpeedLevel(level) {
    def max = getMaxSpeedForDevice()
    level = Math.max(1, Math.min(max, level.toInteger()))

    logDebug "Setting speed level to ${level} (max: ${max})"
    parent.childSetSpeed(device.deviceNetworkId, level)

    sendEvent(name: "speedLevel", value: level)
    sendEvent(name: "speed", value: Math.round((level / max) * 100))

    // Update fan speed name based on device's actual speed levels
    def speedName = getSpeedName(level, max)
    sendEvent(name: "fanSpeed", value: speedName)

    // Ensure device is on when setting speed
    if (device.currentValue("switch") != "on") {
        sendEvent(name: "switch", value: "on")
    }
}

def getSpeedName(level, max) {
    def ratio = level / max
    if (ratio <= 0.25) return "low"
    if (ratio <= 0.5) return "medium-low"
    if (ratio <= 0.75) return "medium-high"
    return "high"
}

def speedUp() {
    def current = device.currentValue("speedLevel") ?: 1
    def max = getMaxSpeedForDevice()
    if (current < max) {
        setSpeedLevel(current + 1)
    }
}

def speedDown() {
    def current = device.currentValue("speedLevel") ?: 2
    if (current > 1) {
        setSpeedLevel(current - 1)
    }
}

def cycleSpeed() {
    def current = device.currentValue("speedLevel") ?: 0
    def max = getMaxSpeedForDevice()

    def next = current + 1
    if (next > max) next = 1

    setSpeedLevel(next)
}

// Mode Control
def setMode(mode) {
    logDebug "Setting mode to ${mode}"
    parent.childSetMode(device.deviceNetworkId, mode)
    sendEvent(name: "mode", value: mode)

    // Ensure device is on
    if (device.currentValue("switch") != "on") {
        sendEvent(name: "switch", value: "on")
    }
}

// Child Lock
def setChildLock(state) {
    logDebug "Setting child lock to ${state}"
    parent.childSetChildLock(device.deviceNetworkId, state)
    sendEvent(name: "childLock", value: state)
}

// Display Control
def setDisplay(state) {
    logDebug "Setting display to ${state}"
    parent.childSetDisplay(device.deviceNetworkId, state)
    sendEvent(name: "display", value: state)
}

// Refresh
def refresh() {
    logDebug "Refreshing device status"
    parent.refreshChildDevice([cid: device.deviceNetworkId])
}

// Helper methods
def logDebug(msg) {
    if (logEnable) log.debug "${device.label ?: device.name}: ${msg}"
}

def logInfo(msg) {
    if (txtEnable) log.info "${device.label ?: device.name}: ${msg}"
}

// Parse method for handling events from parent
def parse(String description) {
    logDebug "parse: ${description}"
}
