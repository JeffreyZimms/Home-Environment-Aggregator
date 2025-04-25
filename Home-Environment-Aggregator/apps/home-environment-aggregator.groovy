definition(
    name: "Home Environment Aggregator and Logic",
    namespace: "zimm",
    author: "Jeffrey Zimmerman",
    description: "Analyze and respond to your home's environmental conditions using smart rules and sensor data.",
    category: "Convenience",
    iconUrl: "",
    iconX2Url: "",
    importUrl: ""
)

preferences {
    page(name: "mainPage", title: "Home Environment Aggregator and Logic Settings", install: true, uninstall: true) {
        section("Select sensors and assign friendly names:") {
            input("sensorList", "capability.temperatureMeasurement", title: "Temperature Sensors", multiple: true, required: true)
            input("humidityList", "capability.relativeHumidityMeasurement", title: "Humidity Sensors (same order)", multiple: true, required: false)
        }
        section("Friendly Names and Floor Mapping") {
            input("friendlyNames", "text", title: "Comma-separated friendly names (same order as sensors)", required: true)
            input("floorAssignments", "text", title: "Comma-separated floor levels (1 or 2 for each sensor)", required: true)
        }
        section("Hub Variables") {
            input("warmestVar", "text", title: "Variable name for warmest room string", required: true)
            input("coolestVar", "text", title: "Variable name for coolest room string", required: true)
            input("humiditySummaryVar", "text", title: "Variable name for humidity summary string", required: false)
            input("avgHomeTempVar", "text", title: "Variable for average home temp", required: false)
            input("floorCompareVar", "text", title: "Variable for floor temp comparison string", required: false)
        }
        section("Humidity Spike Detection") {
            input("monitoredHumiditySensor", "capability.relativeHumidityMeasurement", title: "Sensor to monitor for spike", required: false)
            input("humiditySpikeSwitch", "capability.switch", title: "Virtual switch to turn on if spike is detected", required: false)
            input("humidityThreshold", "number", title: "Humidity % above average to trigger switch", defaultValue: 10, required: false)
            input("cooldownMinutes", "number", title: "Cooldown minutes before switch can turn OFF", defaultValue: 5, required: false)
        }
        section("Exhaust Fan Control") {
            input("exhaustFanSwitch", "capability.switch", title: "Exhaust fan switch", required: false)
            input("bathroomMotionSensor", "capability.motionSensor", title: "Motion sensor in monitored room", required: false)
            input("motionInactiveDelay", "number", title: "Minutes after motion stops before fan can activate", defaultValue: 5, required: false)
        }
    }
}

def installed() {
    initialize()
}

def updated() {
    unsubscribe()
    unschedule()
    initialize()
}

def initialize() {
    sensorList.each { subscribe(it, "temperature", temperatureHandler) }
    humidityList?.each { subscribe(it, "humidity", humidityHandler) }
    if (monitoredHumiditySensor) subscribe(monitoredHumiditySensor, "humidity", humidityHandler)
    if (bathroomMotionSensor) subscribe(bathroomMotionSensor, "motion", motionHandler)
    evaluateTemps()
}

def temperatureHandler(evt) {
    evaluateTemps()
}

def humidityHandler(evt) {
    evaluateTemps()
}

def motionHandler(evt) {
    if (evt.value == "inactive") {
        runIn(motionInactiveDelay * 60, evaluateTemps)
    }
}

def evaluateTemps() {
    if (!sensorList || !friendlyNames || !floorAssignments) return

    def names = friendlyNames.split(',').collect { it.trim() }
    def floors = floorAssignments.split(',').collect { it.trim().toInteger() }
    if (names.size() != sensorList.size() || floors.size() != sensorList.size()) {
        log.warn "Mismatch in number of sensors, names, or floor assignments"
        return
    }

    def temps = sensorList.collect { it.currentTemperature }
    def pairs = [sensorList, temps, names, floors].transpose()
    def warmest = pairs.max { it[1] }
    def coolest = pairs.min { it[1] }
    updateGlobalVariable(warmestVar, "At ${warmest[1]} the ${warmest[2]} is the warmest")
    updateGlobalVariable(coolestVar, "At ${coolest[1]} the ${coolest[2]} is the coolest room")

    def avgTemp = temps.sum() / temps.size()
    if (avgHomeTempVar) updateGlobalVariable(avgHomeTempVar, String.format("%.2f", avgTemp))

    def floor1Temps = pairs.findAll { it[3] == 1 }.collect { it[1] }
    def floor2Temps = pairs.findAll { it[3] == 2 }.collect { it[1] }
    if (floor1Temps && floor2Temps && floorCompareVar) {
        def avg1 = floor1Temps.sum() / floor1Temps.size()
        def avg2 = floor2Temps.sum() / floor2Temps.size()
        def delta = (avg1 - avg2).toDouble().round(2)
        def msg = delta > 0 ? "The 1st floor is ${delta} degrees warmer than the 2nd floor" :
                  delta < 0 ? "The 2nd floor is ${Math.abs(delta)} degrees warmer than the 1st floor" :
                  "Both floors are at the same temperature"
        updateGlobalVariable(floorCompareVar, msg)
    }

    if (humidityList && humiditySummaryVar) {
        def hums = humidityList.collect { it.currentHumidity }
        def avgHum = hums.sum() / hums.size()
        updateGlobalVariable(humiditySummaryVar, "The average humidity across selected rooms is ${String.format("%.1f", avgHum)}%")
    }

    if (monitoredHumiditySensor && humiditySpikeSwitch) {
        def spikeHum = monitoredHumiditySensor.currentHumidity
        def restHums = humidityList.findAll { it.deviceNetworkId != monitoredHumiditySensor.deviceNetworkId }.collect { it.currentHumidity }
        if (restHums && spikeHum != null) {
            def restAvg = restHums.sum() / restHums.size()
            def threshold = humidityThreshold ?: 10
            def diff = spikeHum - restAvg
            def cooldown = cooldownMinutes ?: 5

            if (diff >= threshold) {
                humiditySpikeSwitch.on()
                state.lastHumiditySpike = now()
                log.info "Humidity spike detected (${spikeHum}% vs ${String.format("%.1f", restAvg)}%), switch ON"

                if (bathroomMotionSensor?.currentMotion == "inactive") {
                    def lastInactive = state.lastMotionInactive ?: 0L
                    if ((now() - lastInactive) > (motionInactiveDelay * 60 * 1000)) {
                        exhaustFanSwitch?.on()
                        log.info "Exhaust fan turned ON after humidity spike and inactivity"
                    } else {
                        log.debug "Fan not turned on — waiting for motion inactivity delay"
                    }
                } else {
                    log.debug "Fan not triggered — motion still active"
                }

            } else if (state.lastHumiditySpike && (now() - state.lastHumiditySpike) > (cooldown * 60 * 1000)) {
                humiditySpikeSwitch.off()
                exhaustFanSwitch?.off()
                state.lastHumiditySpike = null
                log.info "Cooldown complete — humidity spike switch OFF and fan OFF"
            }
        }
    }

    if (bathroomMotionSensor?.currentMotion == "inactive") {
        state.lastMotionInactive = now()
    }
}

def updateGlobalVariable(varName, value) {
    try {
        location.getVariables()?.find { it.name == varName }?.update(value)
        log.debug "Updated variable ${varName} with value: ${value}"
    } catch (e) {
        log.warn "Failed to update variable ${varName}: ${e.message}"
    }
}
