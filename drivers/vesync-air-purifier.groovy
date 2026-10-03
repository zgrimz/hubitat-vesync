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
 *  Verified on: Core200S-P, Core400S-P. Other listed models are implemented but not yet verified.
 *
 */

import groovy.transform.Field
import groovy.json.JsonOutput

@Field static final String VERSION = "1.1.1"

// FanControl ENUM values by device speed count, so supportedFanSpeeds matches the hardware
@Field static final Map SPEED_NAMES = [
    3: ["low", "medium", "high"],
    4: ["low", "medium-low", "medium-high", "high"]
]

metadata {
    definition(name: "VeSync Air Purifier", namespace: "vesync", author: "VeSync Hubitat Integration") {
        capability "Switch"
        capability "FanControl"
        capability "Refresh"
        capability "Actuator"
        capability "Sensor"

        // Custom attributes. Note: `speed` and `supportedFanSpeeds` come from FanControl -
        // `speed` is an ENUM there, so the numeric level lives in speedLevel instead.
        attribute "mode", "string"
        attribute "speedLevel", "number"
        attribute "speedPercent", "number"
        attribute "filterLife", "number"
        attribute "airQuality", "string"
        attribute "airQualityIndex", "number"
        attribute "pm25", "number"
        attribute "pm10", "number"
        attribute "childLock", "string"
        attribute "display", "string"
        attribute "deviceStatus", "string"

        // Commands. setSpeed and cycleSpeed are part of FanControl and are not redeclared.
        command "setSpeedLevel", [[name: "Level*", type: "NUMBER", description: "Raw device speed level"]]
        command "setMode", [[name: "Mode*", type: "ENUM", constraints: ["manual", "auto", "sleep", "pet", "turbo"]]]
        command "speedUp"
        command "speedDown"
        command "setChildLock", [[name: "State*", type: "ENUM", constraints: ["on", "off"]]]
        command "setDisplay", [[name: "State*", type: "ENUM", constraints: ["on", "off"]]]
    }

    preferences {
        input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: false
        input name: "txtEnable", type: "bool", title: "Enable description text logging", defaultValue: true
        input name: "maxSpeedOverride", type: "number", title: "Maximum Speed Level (override)",
            range: "1..12", description: "Leave blank to use the level count detected for your model"
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
    publishSupportedSpeeds()
}

// Get device type from stored data
def getDeviceType() {
    return device.getDataValue("deviceType") ?: ""
}

// Max speed comes from the parent app's model table, stamped onto the device at creation.
// An explicit user override wins; there is no sentinel value that silently overwrites it.
def getMaxSpeedForDevice() {
    if (settings.maxSpeedOverride) return settings.maxSpeedOverride.toInteger()
    def stamped = device.getDataValue("maxSpeed")
    return stamped ? stamped.toInteger() : 4
}

// FanControl consumers (dashboards, Alexa, Google) read supportedFanSpeeds to know which
// ENUM values this device accepts.
def publishSupportedSpeeds() {
    def max = getMaxSpeedForDevice()
    def names = (SPEED_NAMES[max] ?: SPEED_NAMES[4]) + ["on", "off", "auto"]
    sendEvent(name: "supportedFanSpeeds", value: JsonOutput.toJson(names))
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
        def name = speed.toLowerCase()
        if (name == "off") { off(); return }
        if (name == "on" || name == "auto") { setMode("auto"); return }

        // Map the ENUM name onto this model's actual level count rather than assuming 4
        def level = levelForName(name, max)
        if (level != null) {
            setSpeedLevel(level)
            return
        }
        logDebug "Unrecognized speed name '${speed}'"
        return
    }

    // Numeric speed (percentage)
    def level = Math.round((speed.toDouble() / 100.0) * max)
    setSpeedLevel(Math.max(1, Math.min(max, level.toInteger())))
}

def setSpeedLevel(level) {
    def max = getMaxSpeedForDevice()
    level = Math.max(1, Math.min(max, level.toInteger()))

    logDebug "Setting speed level to ${level} (max: ${max})"
    if (parent.childSetSpeed(device.deviceNetworkId, level) == false) return false
    publishSpeed(level)
    // A successful submission may optimistically turn on; polled speed must never do so.
    sendEvent(name: "switch", value: "on")
}

// Single place that turns a raw device level into the attributes Hubitat expects. Called
// both by setSpeedLevel and by the parent app after a poll, so a commanded update and a
// polled one always leave the device in the same shape.
def publishSpeed(level) {
    def max = getMaxSpeedForDevice()
    level = Math.max(0, Math.min(max, (level ?: 0).toInteger()))

    sendEvent(name: "speedLevel", value: level)
    sendEvent(name: "speedPercent", value: Math.round((level / max) * 100), unit: "%")
    sendEvent(name: "speed", value: level > 0 ? nameForLevel(level, max) : "off")
}

// ENUM name <-> level, derived from the model's own level count so 3-speed devices can
// actually reach every speed instead of collapsing three names onto level 2.
def nameForLevel(level, max) {
    def names = SPEED_NAMES[max] ?: SPEED_NAMES[4]
    def idx = Math.min(names.size() - 1, Math.max(0, level.toInteger() - 1))
    return names[idx]
}

def levelForName(name, max) {
    def names = SPEED_NAMES[max] ?: SPEED_NAMES[4]
    def idx = names.indexOf(name)
    if (idx >= 0) return idx + 1
    // Names this model doesn't expose (e.g. "medium" on a 4-speed) fall back proportionally
    switch (name) {
        case "low": return 1
        case "medium-low": return Math.max(1, Math.round(max * 0.4))
        case "medium": return Math.max(1, Math.round(max * 0.5))
        case "medium-high": return Math.max(1, Math.round(max * 0.75))
        case "high": return max
    }
    return null
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
def setChildLock(value) {
    logDebug "Setting child lock to ${value}"
    parent.childSetChildLock(device.deviceNetworkId, value)
    sendEvent(name: "childLock", value: value)
}

// Display Control
def setDisplay(value) {
    logDebug "Setting display to ${value}"
    parent.childSetDisplay(device.deviceNetworkId, value)
    sendEvent(name: "display", value: value)
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
