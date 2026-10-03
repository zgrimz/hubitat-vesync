// Run from the repository root: groovy tests/reliability.groovy
// These tests execute the real app/driver methods against a deterministic Hubitat API mock.
import groovy.json.JsonOutput

class FakeDevice {
    String deviceNetworkId
    String label = 'Test device'
    Map data = [deviceType: 'Core400S', maxSpeed: '4', uuid: 'test']
    Map values = [:]
    List events = []
    Script driver
    boolean stopped = false
    def getDataValue(String key) { data[key] }
    def updateDataValue(String key, value) { data[key] = value }
    def currentValue(String key) { values[key] }
    def sendEvent(Map event) { events << event; values[event.name] = event.value; null }
    def publishSpeed(level) { driver.publishSpeed(level) }
    def publishSupportedSpeeds() { driver?.publishSupportedSpeeds() }
    def stopLevelChange() { stopped = true; driver?.stopLevelChange() }
}

class HubMock {
    long timestamp = 1000000L
    List jobs = []
    List requests = []
    Map children = [:]
    Script script
    GroovyClassLoader loader = new GroovyClassLoader()

    HubMock(String path, FakeDevice device = null, Object parent = null) {
        // The light driver imports this Hubitat class; tested paths do not call its methods.
        loader.parseClass('package hubitat.helper; class ColorUtils {}')
        def bindings = new Binding([
            state: [:], atomicState: [tokenExpiry: 999999999999L],
            settings: [:], location: [timeZone: TimeZone.getTimeZone('UTC'), temperatureScale: 'F'],
            device: device, parent: parent, logEnable: false, txtEnable: false,
            log: new Expando(debug: { msg -> }, info: { msg -> }, warn: { msg -> }, error: { msg -> }),
            definition: { Map args -> }, preferences: { Closure block -> }, metadata: { Closure block -> },
            now: { -> timestamp },
            getChildDevice: { cid -> children[cid] },
            getChildDevices: { -> children.values().toList() },
            asynchttpPost: { String callback, Map params, Map data = null ->
                requests << [callback: callback, params: params, data: data]
            },
            runInMillis: { delay, String handler, Map options = [:] ->
                if (options.overwrite != false) jobs.removeAll { it.handler == handler }
                jobs << [due: timestamp + delay, handler: handler, data: options.data]
            },
            runIn: { delay, String handler, Map options = [:] ->
                if (options.overwrite != false) jobs.removeAll { it.handler == handler }
                jobs << [due: timestamp + delay * 1000L, handler: handler, data: options.data]
            },
            schedule: { cron, handler -> },
            unschedule: { String handler = null -> jobs.removeAll { !handler || it.handler == handler } },
            sendEvent: { Map event -> device.sendEvent(event) }
        ])
        script = new GroovyShell(loader, bindings).parse(new File(path).text, new File(path).name.replace('-', '_'))
        script.run()
        script.state.token = 'test-token'
        if (device) device.driver = script
    }

    void advanceTo(long time) {
        int executions = 0
        while (jobs.any { it.due <= time }) {
            assert executions++ < 1000 : 'Unbounded scheduling loop'
            def next = jobs.findAll { it.due <= time }.min { it.due }
            jobs.remove(next)
            timestamp = next.due as Long
            if (next.data == null) script."${next.handler}"()
            else script."${next.handler}"(next.data)
        }
        timestamp = time
    }

    void respond(Map request, int status = 200, Map payload = [code: 0, result: [enabled: false, level: 0]]) {
        script."${request.callback}"([status: status, data: JsonOutput.toJson(payload)], request.data)
    }
}

def appPath = 'apps/vesync-integration.groovy'
def newApp = {
    def hub = new HubMock(appPath)
    ['a', 'b'].each { cid ->
        // Switches keep the status response test independent of fan capability formatting.
        hub.children[cid] = new FakeDevice(deviceNetworkId: cid, data: [deviceType: 'ESWL01', uuid: cid])
    }
    hub
}

// A burst for A creates one pending read; B keeps its own job and request.
def hub = newApp()
100.times { hub.script.refreshChildDevice([cid: 'a']) }
hub.script.refreshChildDevice([cid: 'b'])
assert hub.jobs.count { it.handler == 'performDeviceRefresh' } == 2
hub.advanceTo(hub.timestamp + 1L)
assert hub.requests.size() == 2
assert hub.requests.every { it.params.timeout == 15 }
def requestA = hub.requests.find { it.data.cid == 'a' }
100.times { hub.script.refreshChildDevice([cid: 'a']) }
assert hub.jobs.count { it.handler == 'performDeviceRefresh' } == 0
hub.respond(requestA)
assert hub.jobs.count { it.handler == 'performDeviceRefresh' } == 1
hub.advanceTo(hub.timestamp + 4999L)
assert hub.requests.count { it.data.cid == 'a' } == 1
hub.advanceTo(hub.timestamp + 1L)
assert hub.requests.count { it.data.cid == 'a' } == 2

// A lost callback releases the queued follow-up at guard expiry + failure backoff.
hub = newApp()
hub.script.refreshChildDevice([cid: 'a'])
hub.advanceTo(hub.timestamp + 1L)
def expired = hub.requests.last()
hub.script.refreshChildDevice([cid: 'a'])
hub.advanceTo(hub.timestamp + 30000L)
assert hub.script.getRefreshControl('a').inFlight == null
assert hub.script.getRefreshControl('a').failures == 1
assert hub.jobs.count { it.handler == 'performDeviceRefresh' } == 1
hub.advanceTo(hub.timestamp + 15000L)
def newer = hub.requests.last()
assert newer.data.sequence != expired.data.sequence
hub.respond(expired, 200, [code: 0, result: [deviceStatus: 'on']])
assert hub.children.a.events.empty
assert hub.script.getRefreshControl('a').inFlight == newer.data.sequence
hub.respond(newer, 200, [code: 0, result: [deviceStatus: 'off']])
assert hub.children.a.values.switch == 'off'
assert hub.script.getRefreshControl('a').failures == 0

// HTTP and malformed JSON failures back off; repeated refresh calls cannot bypass it.
hub = newApp()
hub.script.refreshChildDevice([cid: 'a'])
hub.advanceTo(hub.timestamp + 1L)
hub.respond(hub.requests.last(), 503, [:])
100.times { hub.script.refreshChildDevice([cid: 'a']) }
assert hub.jobs.count { it.handler == 'performDeviceRefresh' } == 1
hub.advanceTo(hub.timestamp + 14999L)
assert hub.requests.size() == 1
hub.advanceTo(hub.timestamp + 1L)
hub.script.handleDeviceDetailsResponse([status: 200, data: 'bad JSON'], hub.requests.last().data)
assert hub.script.getRefreshControl('a').retryAfter == hub.timestamp + 30000L
assert (1..20).every { hub.script.refreshBackoffMillis(it) <= 300000L }

// Updating resets jobs/guards, invalidates old responses, and prunes removed devices.
hub = newApp()
hub.script.refreshChildDevice([cid: 'a'])
hub.advanceTo(hub.timestamp + 1L)
def beforeUpdate = hub.requests.last()
hub.script.atomicState.refreshControl_removed = [queued: true]
hub.script.updated()
assert !hub.script.atomicState.containsKey('refreshControl_removed')
assert hub.script.getRefreshControl('a').inFlight == null
hub.respond(beforeUpdate, 200, [code: 0, result: [deviceStatus: 'on']])
assert hub.children.a.events.empty
hub.script.refreshChildDevice([cid: 'a'])
hub.advanceTo(hub.timestamp + 5000L)
assert hub.requests.size() == 2

// A queued job missed during reboot expires and can be requested again.
hub = newApp()
hub.script.refreshChildDevice([cid: 'a'])
hub.jobs.clear()
hub.timestamp += 30001L
hub.script.refreshChildDevice([cid: 'a'])
hub.advanceTo(hub.timestamp + 1L)
assert hub.requests.size() == 1

// Commands are all submitted, while their status reads coalesce per device.
hub = newApp()
10.times { hub.script.childOn('a') }
hub.script.childOn('b')
assert hub.requests.size() == 11
assert hub.requests.every { it.params.timeout == 15 }
hub.requests.toList().each { hub.respond(it, 200, [code: 0]) }
assert hub.jobs.count { it.handler == 'performDeviceRefresh' } == 2
hub.children.a.data.deviceType = 'ESL100'
hub.script.childSetBrightness('a', 50)
hub.respond(hub.requests.last(), 200, [code: 0, result: [code: 1]])
assert hub.children.a.stopped

// Reported off remains authoritative even with a remembered nonzero speed setting.
['vesync-air-purifier.groovy', 'vesync-fan.groovy'].each { filename ->
    def device = new FakeDevice(deviceNetworkId: 'p', values: [switch: 'on'])
    new HubMock('drivers/' + filename, device)
    hub = newApp()
    hub.children.p = device
    if (filename.contains('purifier')) hub.script.updatePurifierDevice(device, [enabled: false, level: 2])
    else hub.script.updateFanDevice(device, [enabled: false, level: 2])
    assert device.values.switch == 'off'
    assert device.events.findAll { it.name == 'switch' }*.value == ['off']
    assert device.values.speedLevel == 2
}

// Cloud status repeatedly resetting the displayed level cannot prolong a ramp.
['vesync-light.groovy', 'vesync-dimmer.groovy'].each { filename ->
    def device = new FakeDevice(deviceNetworkId: 'l', values: [level: 50, switch: 'on'])
    List levels = []
    def parent = new Expando(childSetBrightness: { cid, level ->
        levels << level
        true
    })
    def ramp = new HubMock('drivers/' + filename, device, parent)
    ramp.script.startLevelChange('up')
    while (ramp.jobs.any { it.handler == 'doLevelChange' }) {
        device.values.level = 50
        ramp.advanceTo(ramp.timestamp + 2000L)
    }
    assert levels == [60, 70, 80, 90, 100]
    assert !ramp.script.state.levelChangeRunning
    ramp.script.startLevelChange('down')
    ramp.timestamp = ramp.script.state.levelChangeDeadline as Long
    def beforeDeadline = levels.size()
    ramp.script.doLevelChange()
    assert levels.size() == beforeDeadline
    assert !ramp.script.state.levelChangeRunning
    ramp.script.startLevelChange('down')
    ramp.script.state.levelChangeSteps = 100
    def beforeStepLimit = levels.size()
    ramp.script.doLevelChange()
    assert levels.size() == beforeStepLimit
    assert !ramp.script.state.levelChangeRunning
    parent.childSetBrightness = { cid, level -> false }
    ramp.script.startLevelChange('down')
    assert !ramp.script.state.levelChangeRunning
    assert !ramp.jobs.any { it.handler == 'doLevelChange' }
}

// Compile all remaining drivers with the same supported Groovy parser.
new File('drivers').eachFileMatch(~/.*\.groovy/) { file -> new HubMock(file.path) }
println 'PASS: refresh coalescing, timeout recovery, stale responses, backoff, updates, commands, power state, and bounded ramps'
