/**
 *  VeSync Dimmer
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
 *  VeSync Dimmer Switch Driver
 *
 *  Supports: ESWD16 (dimmer switch with RGB indicator light)
 *
 *  Implemented but not yet verified on hardware.
 *
 */

import groovy.transform.Field

@Field static final String VERSION = "1.1.1"

// Seconds between steps of a startLevelChange ramp. Each step is a cloud API call,
// so a 1s ramp risks tripping VeSync rate limiting on a long press.
@Field static final Integer LEVEL_CHANGE_INTERVAL = 2
@Field static final Integer MAX_LEVEL_CHANGE_STEPS = 100
@Field static final Long MAX_LEVEL_CHANGE_MS = 210000L

metadata {
    definition(name: "VeSync Dimmer", namespace: "vesync", author: "VeSync Hubitat Integration") {
        capability "Switch"
        capability "SwitchLevel"
        capability "Refresh"
        capability "Actuator"
        capability "Light"
        capability "ChangeLevel"

        // Custom attributes for indicator light
        attribute "indicatorLightStatus", "string"
        attribute "indicatorRGB", "string"
        attribute "deviceStatus", "string"

        // Commands
        command "setIndicatorLight", [[name: "State*", type: "ENUM", constraints: ["on", "off"]]]
        command "setIndicatorColor", [
            [name: "Red*", type: "NUMBER", description: "Red value (0-255)", range: "0..255"],
            [name: "Green*", type: "NUMBER", description: "Green value (0-255)", range: "0..255"],
            [name: "Blue*", type: "NUMBER", description: "Blue value (0-255)", range: "0..255"]
        ]
        command "startLevelChange", [[name: "Direction*", type: "ENUM", constraints: ["up", "down"]]]
        command "stopLevelChange"
    }

    preferences {
        input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: false
        input name: "txtEnable", type: "bool", title: "Enable description text logging", defaultValue: true
        input name: "levelChangeStep", type: "number", title: "Level Change Step Size", defaultValue: 10, range: "1..25"
        input name: "minLevel", type: "number", title: "Minimum Brightness Level", defaultValue: 1, range: "1..50"
        input name: "prestaging", type: "bool", title: "Enable Level Prestaging", defaultValue: false,
            description: "Allow level changes while off without turning on"
    }
}

def installed() {
    log.info "VeSync Dimmer installed"
    initialize()
}

def updated() {
    log.info "VeSync Dimmer updated"
    initialize()
}

def initialize() {
    if (logEnable) runIn(1800, "logsOff")
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

// SwitchLevel Capability
def setLevel(level, duration = null) {
    def min = settings.minLevel ?: 1
    level = Math.max(0, Math.min(100, level.toInteger()))

    if (level == 0) {
        off()
        return
    }

    // Enforce minimum level
    level = Math.max(min, level)

    logDebug "Setting level to ${level}%"
    if (parent.childSetBrightness(device.deviceNetworkId, level) == false) {
        stopLevelChange()
        return false
    }
    sendEvent(name: "level", value: level, unit: "%")

    // Turn on if not prestaging
    if (!settings.prestaging || level > 0) {
        if (device.currentValue("switch") != "on") {
            sendEvent(name: "switch", value: "on")
        }
    }
    return true
}

// ChangeLevel Capability
def startLevelChange(direction) {
    logDebug "Starting level change: ${direction}"
    stopLevelChange()
    if (!(direction in ["up", "down"])) return
    state.levelChangeLevel = Math.max(1, Math.min(100, (device.currentValue("level") ?: 50).toInteger()))
    state.levelChangeSteps = 0
    state.levelChangeDeadline = now() + MAX_LEVEL_CHANGE_MS
    state.levelChangeDirection = direction
    state.levelChangeRunning = true
    doLevelChange()
}

def stopLevelChange() {
    logDebug "Stopping level change"
    state.levelChangeRunning = false
    unschedule("doLevelChange")
}

def doLevelChange() {
    if (!state.levelChangeRunning) return
    if (now() >= (state.levelChangeDeadline ?: 0L) ||
        (state.levelChangeSteps ?: 0) >= MAX_LEVEL_CHANGE_STEPS) {
        stopLevelChange()
        return
    }

    // Advance our own target so a stale cloud poll cannot restart or prolong the ramp.
    def currentLevel = state.levelChangeLevel ?: 50
    def step = Math.max(1, Math.min(25, (settings.levelChangeStep ?: 10).toInteger()))
    def min = settings.minLevel ?: 1

    if (state.levelChangeDirection == "up") {
        currentLevel = Math.min(100, currentLevel + step)
    } else {
        currentLevel = Math.max(min, currentLevel - step)
    }

    state.levelChangeLevel = currentLevel
    state.levelChangeSteps = (state.levelChangeSteps ?: 0) + 1
    if (setLevel(currentLevel) == false || !state.levelChangeRunning) {
        stopLevelChange()
        return
    }

    if ((state.levelChangeDirection == "up" && currentLevel < 100) ||
        (state.levelChangeDirection == "down" && currentLevel > min)) {
        runIn(LEVEL_CHANGE_INTERVAL, "doLevelChange")
    } else {
        stopLevelChange()
    }
}

// Indicator Light Control
def setIndicatorLight(value) {
    logDebug "Setting indicator light to ${value}"
    def enabled = value == "on" || value == true
    parent.childSetIndicatorLight(device.deviceNetworkId, enabled)
    sendEvent(name: "indicatorLightStatus", value: value)
}

def setIndicatorColor(red, green, blue) {
    red = Math.max(0, Math.min(255, red.toInteger()))
    green = Math.max(0, Math.min(255, green.toInteger()))
    blue = Math.max(0, Math.min(255, blue.toInteger()))

    logDebug "Setting indicator color to RGB(${red}, ${green}, ${blue})"

    parent.childSetIndicatorColor(device.deviceNetworkId, red, green, blue)

    def rgbHex = String.format("#%02x%02x%02x", red, green, blue)
    sendEvent(name: "indicatorRGB", value: rgbHex)
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
