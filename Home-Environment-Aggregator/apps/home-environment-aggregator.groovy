definition(
    name: "Home Environment Aggregator and Logic",
    namespace: "Zimms",
    author: "Jeffrey Zimmerman",
    description: "Aggregates temperature, humidity, and other sensor data across rooms, calculates floor averages, and provides environmental logic. It also will allow monitor specific areas for humidity spikes and control exhaust fan to normalize within tolerance",
    category: "Convenience",
    iconUrl: "",
    iconX2Url: "",
    version: "1.0.0"
)

preferences {
    section("Areas of Interest") {
        input "numAreas", "number", title: "Number of Areas to Monitor", defaultValue: 5, required: true, submitOnChange: true
        if (numAreas) {
            (1..numAreas).each { index ->
                section("Area ${index}") {
                    input "area_${index}_name", "text", title: "Area Name (e.g., Guestroom, Office)", required: true
                    input "area_${index}_floor", "enum", title: "Floor Number", options: ["1", "2"], defaultValue: "1", required: true
                    input "area_${index}_tempSensors", "capability.temperatureMeasurement", title: "Temperature Sensors for this Area", multiple: true, required: false
                    input "area_${index}_humidSensors", "capability.relativeHumidityMeasurement", title: "Humidity Sensors for this Area (Optional)", multiple: true, required: false
                    input "area_${index}_luxSensors", "capability.illuminanceMeasurement", title: "Lux Sensors for this Area (Optional)", multiple: true, required: false
                    input "area_${index}_motionSensors", "capability.motionSensor", title: "Motion Sensors for this Area (Optional)", multiple: true, required: false
                }
            }
        }
    }
    section("Virtual Omnisensors for Floor Averages") {
        input "floor1Omnisensor", "capability.temperatureMeasurement", title: "First Floor Virtual Omnisensor", required: false
        input "floor2Omnisensor", "capability.temperatureMeasurement", title: "Second Floor Virtual Omnisensor", required: false
    }
    section("Virtual Omnisensors for Room Averages") {
        input "roomOmnisensors", "capability.temperatureMeasurement", title: "Virtual Omnisensors for Each Room (label must match area name, e.g., 'Kitchen Omnisensor')", multiple: true, required: false
    }
    section("Whole Home Omnisensor") {
        input "wholeHomeOmnisensor", "capability.temperatureMeasurement", title: "Whole Home Omnisensor", required: false
    }
    section("Hub Variable Connectors") {
        input "coolRoomConnector", "capability.variable", title: "Variable Connector for Coolest Room", required: true
        input "warmRoomConnector", "capability.variable", title: "Variable Connector for Warmest Room", required: true
        input "floorDeltaConnector", "capability.variable", title: "Variable Connector for Floor Delta", required: true
        input "homeHumidityConnector", "capability.variable", title: "Variable Connector for Average Humidity", required: true
        input "averageHumidityDevice", "capability.sensor", title: "Virtual Device for Numeric Average Humidity", required: false
    }
    section("Floor Delta Notification") {
        input "deltaThreshold", "number", title: "Floor Delta Notification Threshold (degrees)", defaultValue: 5, required: false
        input "notificationDevice", "capability.notification", title: "Notification Device", required: false
    }
    section("Humidity Spike Detection") {
        input "spikeSensor", "capability.relativeHumidityMeasurement", title: "Humidity Sensor to Monitor for Spikes", required: false
        input "spikeThreshold", "number", title: "Humidity Spike Threshold (% above average)", defaultValue: 10, required: false
        input "spikeSwitch", "capability.switch", title: "Virtual Switch for Spike Detection", required: false
        input "exhaustFan", "capability.switch", title: "Exhaust Fan Switch", required: false
        input "motionSensor", "capability.motionSensor", title: "Motion Sensor for Fan Control", required: false
        input "motionDelay", "number", title: "Motion Inactive Delay (minutes)", defaultValue: 5, required: false
        input "cooldownMinutes", "number", title: "Cooldown Period (minutes)", defaultValue: 5, required: false
        input "humidityDeltaDevice", "capability.sensor", title: "Virtual Device for Humidity Delta Tracking", required: false
    }
    section("Logging") {
        input "logLevel", "enum", title: "Logging Level", options: ["None", "Error", "Debug"], defaultValue: "Debug", required: true
    }
}

def installed() {
    logDebug "Installed with settings: ${settings}"
    initialize()
}

def updated() {
    logDebug "Updated with settings: ${settings}"
    unsubscribe()
    initialize()
}

def initialize() {
    // Build a list of all sensors for subscriptions
    def allTempSensors = []
    def allHumidSensors = []
    def allLuxSensors = []
    def allMotionSensors = []
    (1..(settings.numAreas ?: 1)).each { index ->
        def tempSensors = settings["area_${index}_tempSensors"]
        def humidSensors = settings["area_${index}_humidSensors"]
        def luxSensors = settings["area_${index}_luxSensors"]
        def motionSensors = settings["area_${index}_motionSensors"]
        if (tempSensors) allTempSensors += tempSensors
        if (humidSensors) allHumidSensors += humidSensors
        if (luxSensors) allLuxSensors += luxSensors
        if (motionSensors) allMotionSensors += motionSensors
    }
    // Subscribe to sensor events
    if (allTempSensors) subscribe(allTempSensors, "temperature", sensorChangeHandler)
    if (allHumidSensors) subscribe(allHumidSensors, "humidity", sensorChangeHandler)
    if (allLuxSensors) subscribe(allLuxSensors, "illuminance", sensorChangeHandler)
    if (allMotionSensors) subscribe(allMotionSensors, "motion", sensorChangeHandler)
    if (spikeSensor && motionSensor) subscribe(motionSensor, "motion", motionHandler)
    
    // Initialize lastMotionInactive based on current motion sensor state
    if (motionSensor) {
        if (motionSensor.currentMotion == "inactive" && !state.lastMotionInactive) {
            state.lastMotionInactive = now()
            logDebug "Initialized lastMotionInactive to ${state.lastMotionInactive} as motion sensor is inactive"
        } else if (motionSensor.currentMotion == "active") {
            state.lastMotionInactive = 0
            logDebug "Motion sensor is active, lastMotionInactive set to 0"
        }
    }
    
    validateInputs()
    evaluateEnvironment()
}

def sensorChangeHandler(evt) {
    def now = now()
    def lastUpdate = state.lastEnvironmentUpdate ?: 0
    def throttleMillis = 10000 // 10 seconds
    
    if (now - lastUpdate >= throttleMillis) {
        state.lastEnvironmentUpdate = now
        evaluateEnvironment()
        logDebug "Running evaluateEnvironment due to sensor change: ${evt.device.displayName} ${evt.name} = ${evt.value}"
    } else {
        logDebug "Throttling evaluateEnvironment; last update was ${(now - lastUpdate)/1000} seconds ago"
    }
}

def validateInputs() {
    def areaNames = []
    def hasFloor1Lux = false
    def hasFloor2Lux = false
    (1..(settings.numAreas ?: 1)).each { index ->
        def areaName = settings["area_${index}_name"]
        def floor = settings["area_${index}_floor"]
        def tempSensors = settings["area_${index}_tempSensors"]
        def humidSensors = settings["area_${index}_humidSensors"]
        def luxSensors = settings["area_${index}_luxSensors"]
        def motionSensors = settings["area_${index}_motionSensors"]
        
        if (!areaName) {
            logError "Area ${index} is missing a name"
        } else {
            areaNames << areaName
        }
        if (!floor) {
            logError "Area ${index} (${areaName}) is missing a floor assignment"
        }
        if (!tempSensors) {
            logDebug "Area ${index} (${areaName}) has no temperature sensors"
        }
        if (humidSensors) {
            humidSensors.each { sensor ->
                if (!sensor.hasCapability("RelativeHumidityMeasurement")) {
                    logError "Sensor ${sensor.displayName} in Area ${index} (${areaName}) does not support humidity measurement"
                }
            }
        }
        if (luxSensors) {
            humidSensors.each { sensor ->
                if (!sensor.hasCapability("IlluminanceMeasurement")) {
                    logError "Sensor ${sensor.displayName} in Area ${index} (${areaName}) does not support illuminance measurement"
                }
            }
            if (floor == "1") hasFloor1Lux = true
            if (floor == "2") hasFloor2Lux = true
        }
        if (motionSensors) {
            motionSensors.each { sensor ->
                if (!sensor.hasCapability("MotionSensor")) {
                    logError "Sensor ${sensor.displayName} in Area ${index} (${areaName}) does not support motion detection"
                }
            }
        }
    }
    // Validate room omnisensors
    if (roomOmnisensors) {
        def uniqueAreaNames = areaNames.toSet()
        def omnisensorNames = roomOmnisensors.collect { it.displayName }
        if (roomOmnisensors.size() != uniqueAreaNames.size()) {
            logError "Number of room omnisensors (${roomOmnisensors.size()}) does not match number of unique area names (${uniqueAreaNames.size()})"
        }
        uniqueAreaNames.each { areaName ->
            def expectedName = "${areaName} Omnisensor"
            if (!omnisensorNames.contains(expectedName)) {
                logError "No omnisensor found with name '${expectedName}' to match area name '${areaName}'"
            }
        }
        roomOmnisensors.each { omni ->
            def areaIndex = (1..(settings.numAreas ?: 1)).find { settings["area_${it}_name"] == omni.displayName.replace(" Omnisensor", "") }
            def luxSensors = areaIndex ? settings["area_${areaIndex}_luxSensors"] : null
            if (!omni.hasCapability("TemperatureMeasurement")) {
                logError "Room Omnisensor ${omni.displayName} does not support temperature measurement"
            }
            if (!omni.hasCapability("RelativeHumidityMeasurement")) {
                logError "Room Omnisensor ${omni.displayName} does not support humidity measurement"
            }
            if (luxSensors && !omni.hasCapability("IlluminanceMeasurement")) {
                logError "Room Omnisensor ${omni.displayName} does not support illuminance measurement, but lux sensors are configured for its area"
            }
        }
    }
    // Validate floor omnisensors for illuminance capability
    if (floor1Omnisensor) {
        if (!floor1Omnisensor.hasCapability("TemperatureMeasurement")) {
            logError "First Floor Omnisensor does not support temperature measurement"
        }
        if (hasFloor1Lux && !floor1Omnisensor.hasCapability("IlluminanceMeasurement")) {
            logError "First Floor Omnisensor does not support illuminance measurement, but lux sensors are configured on the first floor"
        }
    }
    if (floor2Omnisensor) {
        if (!floor2Omnisensor.hasCapability("TemperatureMeasurement")) {
            logError "Second Floor Omnisensor does not support temperature measurement"
        }
        if (hasFloor2Lux && !floor2Omnisensor.hasCapability("IlluminanceMeasurement")) {
            logError "Second Floor Omnisensor does not support illuminance measurement, but lux sensors are configured on the second floor"
        }
    }
    // Validate whole home omnisensor
    if (wholeHomeOmnisensor) {
        if (!wholeHomeOmnisensor.hasCapability("TemperatureMeasurement")) {
            logError "Whole Home Omnisensor does not support temperature measurement"
        }
        if (!wholeHomeOmnisensor.hasCapability("RelativeHumidityMeasurement")) {
            logError "Whole Home Omnisensor does not support humidity measurement"
        }
        if (!wholeHomeOmnisensor.hasCapability("IlluminanceMeasurement")) {
            logError "Whole Home Omnisensor does not support illuminance measurement"
        }
    }
    if (deltaThreshold && deltaThreshold <= 0) {
        logError "Delta threshold must be a positive number"
    }
    // Validate humidity delta device
    if (humidityDeltaDevice) {
        if (!humidityDeltaDevice.hasAttribute("humidityDelta")) {
            logError "Humidity Delta Device does not support humidityDelta attribute"
        }
        if (!humidityDeltaDevice.hasAttribute("threshold")) {
            logError "Humidity Delta Device does not support threshold attribute"
        }
    }
    // Validate average humidity device
    if (averageHumidityDevice) {
        if (!averageHumidityDevice.hasAttribute("averageHumidity")) {
            logError "Average Humidity Device does not support averageHumidity attribute"
        }
    }
}

def evaluateEnvironment() {
    // --- Temperature Evaluation ---
    def areaTemps = [:]
    def roomTemps = [:]
    def allTemps = []
    (1..(settings.numAreas ?: 1)).each { index ->
        def areaName = settings["area_${index}_name"]
        def floor = settings["area_${index}_floor"]?.toInteger() ?: 1
        def tempSensors = settings["area_${index}_tempSensors"]
        
        if (!areaName || !tempSensors) return
        
        def temps = []
        tempSensors.each { sensor ->
            def temp = sensor.currentTemperature?.toDouble()
            if (temp != null) {
                logDebug "Sensor ${sensor.displayName} (Area: ${areaName}) temperature: ${temp}"
                temps << temp
                allTemps << temp
            } else {
                logDebug "No valid temperature from ${sensor.displayName} (Area: ${areaName})"
            }
        }
        
        if (temps) {
            def avgTemp = temps.sum() / temps.size()
            roomTemps[areaName] = avgTemp
            areaTemps[areaName] = [temps: temps, floor: floor]
            
            // Update room omnisensor
            if (roomOmnisensors) {
                def omni = roomOmnisensors.find { it.displayName == "${areaName} Omnisensor" }
                if (omni) {
                    def roundedTemp = Double.valueOf(String.format('%.2f', avgTemp))
                    omni.sendEvent(name: "temperature", value: roundedTemp)
                    logDebug "Set ${areaName} Omnisensor temperature to ${roundedTemp}"
                }
            }
        }
    }
    
    if (!areaTemps) {
        logDebug "No valid temperature data"
        return
    }
    
    // Update Whole Home Omnisensor with average temperature
    if (wholeHomeOmnisensor && allTemps) {
        def wholeHomeAvgTemp = allTemps.sum() / allTemps.size()
        def roundedWholeHomeTemp = Double.valueOf(String.format('%.2f', wholeHomeAvgTemp))
        wholeHomeOmnisensor.sendEvent(name: "temperature", value: roundedWholeHomeTemp)
        logDebug "Set Whole Home Omnisensor temperature to ${roundedWholeHomeTemp}"
    }
    
    // Find warmest and coolest rooms
    def coolest = roomTemps.min { it.value }
    def warmest = roomTemps.max { it.value }
    
    if (coolest) {
        def roundedCoolTemp = Double.valueOf(String.format('%.2f', coolest.value))
        def coolText = "At ${roundedCoolTemp} the ${coolest.key} is the coolest room"
        setHubVariable(coolRoomConnector, coolText)
        logDebug coolText
    }
    if (warmest) {
        def roundedWarmTemp = Double.valueOf(String.format('%.2f', warmest.value))
        def warmText = "At ${roundedWarmTemp} the ${warmest.key} is the warmest room"
        setHubVariable(warmRoomConnector, warmText)
        logDebug warmText
    }
    
    // Calculate floor averages
    def floor1Temps = []
    def floor2Temps = []
    areaTemps.each { areaName, data ->
        if (data.floor == 1) {
            floor1Temps += data.temps
        } else if (data.floor == 2) {
            floor2Temps += data.temps
        }
    }
    def avgFloor1 = floor1Temps ? floor1Temps.sum() / floor1Temps.size() : null
    def avgFloor2 = floor2Temps ? floor2Temps.sum() / floor2Temps.size() : null
    
    // Update floor delta and check for notification
    if (avgFloor1 && avgFloor2) {
        def delta = avgFloor2 - avgFloor1
        def absDelta = Math.abs(delta)
        def roundedDelta = Double.valueOf(String.format('%.2f', absDelta))
        def deltaText = "The second floor is ${roundedDelta} degrees ${delta >= 0 ? 'warmer' : 'cooler'} than the first floor"
        setHubVariable(floorDeltaConnector, deltaText)
        logDebug deltaText
        
        if (notificationDevice && deltaThreshold) {
            def threshold = deltaThreshold.toDouble()
            def isAboveThreshold = absDelta >= threshold
            def wasAboveThreshold = state.deltaNotified ?: false
            
            if (isAboveThreshold && !wasAboveThreshold) {
                def message = "Floor temperature delta exceeded ${threshold}°F: ${deltaText}"
                notificationDevice.deviceNotification(message)
                logDebug "Sent notification: ${message}"
                state.deltaNotified = true
            } else if (!isAboveThreshold && wasAboveThreshold) {
                state.deltaNotified = false
                logDebug "Delta below threshold, reset notification state"
            }
        }
    }
    
    // Update virtual omnisensors with floor temperature
    if (floor1Omnisensor && avgFloor1) {
        def roundedFloor1Temp = Double.valueOf(String.format('%.2f', avgFloor1))
        floor1Omnisensor.sendEvent(name: "temperature", value: roundedFloor1Temp)
        logDebug "Set First Floor Omnisensor temperature to ${roundedFloor1Temp}"
    }
    if (floor2Omnisensor && avgFloor2) {
        def roundedFloor2Temp = Double.valueOf(String.format('%.2f', avgFloor2))
        floor2Omnisensor.sendEvent(name: "temperature", value: roundedFloor2Temp)
        logDebug "Set Second Floor Omnisensor temperature to ${roundedFloor2Temp}"
    }
    
    // --- Humidity Evaluation ---
    def areaHumidities = [:]
    def roomHumidities = [:]
    def allHumidities = []
    (1..(settings.numAreas ?: 1)).each { index ->
        def areaName = settings["area_${index}_name"]
        def floor = settings["area_${index}_floor"]?.toInteger() ?: 1
        def humidSensors = settings["area_${index}_humidSensors"]
        
        if (!areaName || !humidSensors) return
        
        def humidities = []
        humidSensors.each { sensor ->
            def humidity = sensor.currentHumidity?.toDouble()
            if (humidity != null) {
                logDebug "Sensor ${sensor.displayName} (Area: ${areaName}) humidity: ${humidity}"
                humidities << humidity
                allHumidities << humidity
            } else {
                logDebug "No valid humidity from ${sensor.displayName} (Area: ${areaName})"
            }
        }
        
        if (humidities) {
            def avgHumid = humidities.sum() / humidities.size()
            roomHumidities[areaName] = avgHumid
            areaHumidities[areaName] = [humidities: humidities, floor: floor]
            
            // Update room omnisensor
            if (roomOmnisensors) {
                def omni = roomOmnisensors.find { it.displayName == "${areaName} Omnisensor" }
                if (omni && omni.hasCapability("RelativeHumidityMeasurement")) {
                    def roundedHumid = Double.valueOf(String.format('%.2f', avgHumid))
                    omni.sendEvent(name: "humidity", value: roundedHumid)
                    logDebug "Set ${areaName} Omnisensor humidity to ${roundedHumid} %"
                }
            }
        }
    }
    
    if (areaHumidities) {
        def avgHumidity = allHumidities ? allHumidities.sum() / allHumidities.size() : null
        if (avgHumidity) {
            def roundedHumidity = Double.valueOf(String.format('%.2f', avgHumidity))
            def humidText = "The average home humidity level is ${roundedHumidity} %"
            setHubVariable(homeHumidityConnector, humidText)
            logDebug humidText
            
            // Update the numeric average humidity device
            if (averageHumidityDevice) {
                averageHumidityDevice.sendEvent(name: "averageHumidity", value: roundedHumidity)
                logDebug "Updated Average Humidity Device: averageHumidity=${roundedHumidity}"
            }
            
            // Update Whole Home Omnisensor with average humidity
            if (wholeHomeOmnisensor) {
                wholeHomeOmnisensor.sendEvent(name: "humidity", value: roundedHumidity)
                logDebug "Set Whole Home Omnisensor humidity to ${roundedHumidity} %"
            }
        }
        
        def floor1Humidities = []
        def floor2Humidities = []
        areaHumidities.each { areaName, data ->
            if (data.floor == 1) {
                floor1Humidities += data.humidities
            } else if (data.floor == 2) {
                floor2Humidities += data.humidities
            }
        }
        def avgFloor1Humidity = floor1Humidities ? floor1Humidities.sum() / floor1Humidities.size() : null
        def avgFloor2Humidity = floor2Humidities ? floor2Humidities.sum() / floor2Humidities.size() : null
        
        if (floor1Omnisensor && avgFloor1Humidity) {
            def roundedFloor1Humid = Double.valueOf(String.format('%.2f', avgFloor1Humidity))
            floor1Omnisensor.sendEvent(name: "humidity", value: roundedFloor1Humid)
            logDebug "Set First Floor Omnisensor humidity to ${roundedFloor1Humid} %"
        }
        if (floor2Omnisensor && avgFloor2Humidity) {
            def roundedFloor2Humid = Double.valueOf(String.format('%.2f', avgFloor2Humidity))
            floor2Omnisensor.sendEvent(name: "humidity", value: roundedFloor2Humid)
            logDebug "Set Second Floor Omnisensor humidity to ${roundedFloor2Humid} %"
        }
    }
    
    // --- Lux Evaluation ---
    def areaLuxes = [:]
    def roomLuxes = [:]
    def allLuxes = []
    (1..(settings.numAreas ?: 1)).each { index ->
        def areaName = settings["area_${index}_name"]
        def floor = settings["area_${index}_floor"]?.toInteger() ?: 1
        def luxSensors = settings["area_${index}_luxSensors"]
        
        if (!areaName || !luxSensors) return
        
        def luxValues = []
        luxSensors.each { sensor ->
            def lux = sensor.currentIlluminance?.toDouble()
            if (lux != null) {
                logDebug "Sensor ${sensor.displayName} (Area: ${areaName}) illuminance: ${lux} lux"
                luxValues << lux
                allLuxes << lux
            } else {
                logDebug "No valid illuminance from ${sensor.displayName} (Area: ${areaName})"
            }
        }
        
        if (luxValues) {
            def avgLux = luxValues.sum() / luxValues.size()
            roomLuxes[areaName] = avgLux
            areaLuxes[areaName] = [luxes: luxValues, floor: floor]
            
            // Update room omnisensor
            if (roomOmnisensors) {
                def omni = roomOmnisensors.find { it.displayName == "${areaName} Omnisensor" }
                if (omni && omni.hasCapability("IlluminanceMeasurement")) {
                    def roundedLux = Double.valueOf(String.format('%.2f', avgLux))
                    omni.sendEvent(name: "illuminance", value: roundedLux)
                    logDebug "Set ${areaName} Omnisensor illuminance to ${roundedLux} lux"
                }
            }
        }
    }
    
    // Calculate floor lux averages
    if (areaLuxes) {
        def floor1Luxes = []
        def floor2Luxes = []
        areaLuxes.each { areaName, data ->
            if (data.floor == 1) {
                floor1Luxes += data.luxes
            } else if (data.floor == 2) {
                floor2Luxes += data.luxes
            }
        }
        def avgFloor1Lux = floor1Luxes ? floor1Luxes.sum() / floor1Luxes.size() : null
        def avgFloor2Lux = floor2Luxes ? floor2Luxes.sum() / floor2Luxes.size() : null
        
        if (floor1Omnisensor && avgFloor1Lux) {
            def roundedFloor1Lux = Double.valueOf(String.format('%.2f', avgFloor1Lux))
            floor1Omnisensor.sendEvent(name: "illuminance", value: roundedFloor1Lux)
            logDebug "Set First Floor Omnisensor illuminance to ${roundedFloor1Lux} lux"
        }
        if (floor2Omnisensor && avgFloor2Lux) {
            def roundedFloor2Lux = Double.valueOf(String.format('%.2f', avgFloor2Lux))
            floor2Omnisensor.sendEvent(name: "illuminance", value: roundedFloor2Lux)
            logDebug "Set Second Floor Omnisensor illuminance to ${roundedFloor2Lux} lux"
        }
        
        // Update Whole Home Omnisensor with average lux
        if (wholeHomeOmnisensor && allLuxes) {
            def wholeHomeAvgLux = allLuxes.sum() / allLuxes.size()
            def roundedWholeHomeLux = Double.valueOf(String.format('%.2f', wholeHomeAvgLux))
            wholeHomeOmnisensor.sendEvent(name: "illuminance", value: roundedWholeHomeLux)
            logDebug "Set Whole Home Omnisensor illuminance to ${roundedWholeHomeLux} lux"
        }
    }
    
    if (spikeSensor) {
        evaluateHumiditySpike()
    }
}

def evaluateHumiditySpike() {
    def allHumidities = []
    (1..(settings.numAreas ?: 1)).each { index ->
        def humidSensors = settings["area_${index}_humidSensors"]
        if (humidSensors) {
            humidSensors.each { sensor ->
                def humidity = sensor.currentHumidity?.toDouble()
                if (humidity != null) {
                    allHumidities << humidity
                }
            }
        }
    }
    
    if (!allHumidities) {
        logDebug "No humidity data available for spike detection"
        return
    }
    
    def avgHumidity = allHumidities.sum() / allHumidities.size()
    def spikeValue = spikeSensor.currentHumidity?.toDouble()
    
    if (spikeValue == null) {
        logDebug "No valid humidity from spike sensor ${spikeSensor.displayName}"
        return
    }
    
    def threshold = spikeThreshold?.toDouble() ?: 10.0
    def spikeDetected = spikeValue >= (avgHumidity + threshold)
    
    // Calculate and update humidity delta and threshold
    if (humidityDeltaDevice) {
        def humidityDelta = spikeValue - avgHumidity
        def thresholdValue = avgHumidity + threshold
        def roundedDelta = Double.valueOf(String.format('%.2f', humidityDelta))
        def roundedThreshold = Double.valueOf(String.format('%.2f', thresholdValue))
        humidityDeltaDevice.sendEvent(name: "humidityDelta", value: roundedDelta)
        humidityDeltaDevice.sendEvent(name: "threshold", value: roundedThreshold)
        logDebug "Updated Humidity Delta Device: delta=${roundedDelta}, threshold=${roundedThreshold}"
    }
    
    if (spikeDetected && spikeSwitch) {
        logDebug "Humidity spike detected: ${spikeValue}% (average: ${avgHumidity}%, threshold: ${threshold}%)"
        spikeSwitch.on()
        if (exhaustFan && motionSensor) {
            logDebug "Motion sensor state: ${motionSensor.currentMotion}, last inactive: ${state.lastMotionInactive}"
            if (motionSensor.currentMotion == "inactive") {
                def lastMotion = state.lastMotionInactive ?: 0
                def now = now()
                def delayMillis = (motionDelay?.toInteger() ?: 5) * 60 * 1000
                if (now - lastMotion >= delayMillis) {
                    exhaustFan.on()
                    logDebug "Turned on exhaust fan due to humidity spike and no motion (inactive for ${delayMillis/60000} minutes)"
                } else {
                    logDebug "Motion too recent (inactive for ${(now - lastMotion)/60000} minutes, required: ${delayMillis/60000} minutes), delaying fan activation"
                }
            } else {
                logDebug "Motion sensor is active, fan activation delayed until motion is inactive"
            }
        }
    } else if (!spikeDetected && spikeSwitch) {
        logDebug "No humidity spike: ${spikeValue}% (average: ${avgHumidity}%, threshold: ${threshold}%)"
        spikeSwitch.off()
        if (exhaustFan && exhaustFan.currentSwitch == "on") {
            scheduleFanCooldown()
        }
    }
}

def motionHandler(evt) {
    if (evt.value == "inactive") {
        state.lastMotionInactive = now()
        logDebug "Motion sensor transitioned to inactive, lastMotionInactive set to ${state.lastMotionInactive}"
        if (spikeSwitch?.currentSwitch == "on" && exhaustFan) {
            def lastMotion = state.lastMotionInactive ?: 0
            def now = now()
            def delayMillis = (motionDelay?.toInteger() ?: 5) * 60 * 1000
            if (now - lastMotion >= delayMillis) {
                exhaustFan.on()
                logDebug "Motion inactive for ${delayMillis/60000} minutes, turned on exhaust fan due to humidity spike"
            } else {
                logDebug "Motion inactive, but too recent (${(now - lastMotion)/60000} minutes, required: ${delayMillis/60000} minutes)"
            }
        }
    }
}

def scheduleFanCooldown() {
    def cooldown = cooldownMinutes?.toInteger() ?: 5
    runIn(cooldown * 60, turnOffExhaustFan)
    logDebug "Scheduling exhaust fan cooldown in ${cooldown} minutes"
}

def turnOffExhaustFan() {
    if (exhaustFan?.currentSwitch == "on") {
        exhaustFan.off()
        logDebug "Turned off exhaust fan after delay"
    }
    if (spikeSwitch?.currentSwitch == "on") {
        spikeSwitch.off()
        logDebug "Turned off spike switch after fan cooldown"
    }
}

def turnOffSpikeSwitch() {
    if (spikeSwitch?.currentSwitch == "on") {
        spikeSwitch.off()
        logDebug "Turned off spike switch after cooldown"
    }
}

def setHubVariable(connector, value) {
    if (connector) {
        connector.setVariable(value)
        logDebug "Set ${connector.displayName} to ${value}"
    }
}

def logDebug(msg) {
    if (logLevel == "Debug") {
        log.debug msg
    }
}

def logError(msg) {
    if (logLevel in ["Error", "Debug"]) {
        log.error msg
    }
}
