/**
 *  VeSync Fan
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
 *  VeSync Tower Fan Driver
 *
 *  Supports: LTF-F422S series tower fans
 *
 *  Implemented but not yet verified on hardware.
 *
 */

import groovy.transform.Field
import groovy.json.JsonOutput

@Field static final String VERSION = "1.1.1"

// FanControl ENUM values this driver exposes, coarser than the device's raw level count
@Field static final List SPEED_NAMES = ["low", "medium-low", "medium", "medium-high", "high"]

metadata {
    definition(name: "VeSync Fan", namespace: "vesync", author: "VeSync Hubitat Integration") {
        capability "Switch"
        capability "FanControl"
        capability "Refresh"
        capability "Actuator"

        // Custom attributes. `speed` and `supportedFanSpeeds` come from FanControl, where
        // `speed` is an ENUM - the raw device level lives in speedLevel.
        attribute "mode", "string"
        attribute "speedLevel", "number"
        attribute "speedPercent", "number"
        attribute "oscillation", "string"
        attribute "childLock", "string"
        attribute "display", "string"
        attribute "timer", "number"
        attribute "deviceStatus", "string"

        // Commands. setSpeed and cycleSpeed are part of FanControl and are not redeclared.
        command "setSpeedLevel", [[name: "Level*", type: "NUMBER", description: "Speed level (1-12)"]]
        command "setMode", [[name: "Mode*", type: "ENUM", constraints: ["normal", "auto", "sleep", "turbo"]]]
        command "setOscillation", [[name: "State*", type: "ENUM", constraints: ["on", "off"]]]
        command "setChildLock", [[name: "State*", type: "ENUM", constraints: ["on", "off"]]]
        command "setDisplay", [[name: "State*", type: "ENUM", constraints: ["on", "off"]]]
        command "setTimer", [[name: "Hours*", type: "NUMBER", description: "Timer in hours (0-12)", range: "0..12"]]
        command "speedUp"
        command "speedDown"
        command "toggleOscillation"
    }

    preferences {
        input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: false
        input name: "txtEnable", type: "bool", title: "Enable description text logging", defaultValue: true
        input name: "maxSpeedOverride", type: "number", title: "Maximum Speed Level (override)",
            range: "1..12", description: "Leave blank to use the level count detected for your model"
    }
}

def installed() {
    log.info "VeSync Fan installed"
    initialize()
}

def updated() {
    log.info "VeSync Fan updated"
    initialize()
}

def initialize() {
    if (logEnable) runIn(1800, "logsOff")
    publishSupportedSpeeds()
}

// Max speed comes from the parent app's model table, stamped onto the device at creation.
// An explicit user override wins.
def getMaxSpeedForDevice() {
    if (settings.maxSpeedOverride) return settings.maxSpeedOverride.toInteger()
    def stamped = device.getDataValue("maxSpeed")
    return stamped ? stamped.toInteger() : 12
}

// FanControl consumers (dashboards, Alexa, Google) read supportedFanSpeeds.
def publishSupportedSpeeds() {
    sendEvent(name: "supportedFanSpeeds",
        value: JsonOutput.toJson(SPEED_NAMES + ["on", "off", "auto"]))
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

        def idx = SPEED_NAMES.indexOf(name)
        if (idx < 0) {
            logDebug "Unrecognized speed name '${speed}'"
            return
        }
        // Spread the five ENUM names evenly across this device's level range
        setSpeedLevel(Math.max(1, Math.round(max * (idx + 1) / (double) SPEED_NAMES.size())))
        return
    }

    // Numeric speed (percentage 0-100)
    def level = Math.round((speed.toDouble() / 100.0) * max)
    setSpeedLevel(Math.max(1, Math.min(max, level.toInteger())))
}

def setSpeedLevel(level) {
    def max = getMaxSpeedForDevice()
    level = Math.max(1, Math.min(max, level.toInteger()))

    logDebug "Setting speed level to ${level}"
    if (parent.childSetSpeed(device.deviceNetworkId, level) == false) return false
    publishSpeed(level)
    // A successful submission may optimistically turn on; polled speed must never do so.
    sendEvent(name: "switch", value: "on")

    // Set to normal mode when manually changing speed
    sendEvent(name: "mode", value: "normal")
}

// Single place that turns a raw device level into the attributes Hubitat expects. Called
// both by setSpeedLevel and by the parent app after a poll.
def publishSpeed(level) {
    def max = getMaxSpeedForDevice()
    level = Math.max(0, Math.min(max, (level ?: 0).toInteger()))

    sendEvent(name: "speedLevel", value: level)
    sendEvent(name: "speedPercent", value: Math.round((level / max) * 100), unit: "%")
    sendEvent(name: "speed", value: level > 0 ? getSpeedName(level, max) : "off")
}

def getSpeedName(level, max) {
    def ratio = level / (double) max
    if (ratio <= 0.2) return "low"
    if (ratio <= 0.4) return "medium-low"
    if (ratio <= 0.6) return "medium"
    if (ratio <= 0.8) return "medium-high"
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

    // Cycle through low -> medium -> high -> low
    def next
    if (current <= max / 3) {
        next = Math.round(max / 2)
    } else if (current <= max * 2 / 3) {
        next = max
    } else {
        next = 1
    }

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

// Oscillation
def setOscillation(value) {
    logDebug "Setting oscillation to ${value}"
    parent.childSetOscillation(device.deviceNetworkId, value)
    sendEvent(name: "oscillation", value: value)
}

def toggleOscillation() {
    def current = device.currentValue("oscillation") ?: "off"
    setOscillation(current == "on" ? "off" : "on")
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

// Timer
def setTimer(hours) {
    hours = Math.max(0, Math.min(12, hours.toInteger()))
    logDebug "Setting timer to ${hours} hours"

    parent.childSetTimer(device.deviceNetworkId, hours)
    sendEvent(name: "timer", value: hours)

    if (hours > 0) {
        // Schedule to update timer status
        runIn(hours * 3600, "timerExpired")
    } else {
        unschedule("timerExpired")
    }
}

def timerExpired() {
    logInfo "Timer expired"
    sendEvent(name: "timer", value: 0)
    sendEvent(name: "switch", value: "off")
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
