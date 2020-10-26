/**
 *  Eric Lajoie
 *
 *  Version: 1.0.0.0
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at:
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 *  NOTE: This is an auto-generated file and most comments have been removed!
 *
 */


metadata {
	definition (name: "SNZB-02 Temperature & Humidity Sensor", namespace: "erilaj", author: "Eric Lajoie") {
        capability "Sensor"
        capability "Configuration"
        capability "Refresh"       
        capability "Battery"
        capability "TemperatureMeasurement"
        capability "RelativeHumidityMeasurement"     
    }

    preferences {
        input(name: "debugLogging", type: "bool", title: "Enable debug logging", description: "", defaultValue: false, submitOnChange: true, displayDuringSetup: false, required: false)
        input(name: "infoLogging", type: "bool", title: "Enable info logging", description: "", defaultValue: true, submitOnChange: true, displayDuringSetup: false, required: false)
	}
    
    fingerprint profileId:"0104", endpointId:"01", inClusters:"0000,0003,0402,0405,0001", outClusters:"0003", model:"DS01", manufacturer:"eWeLink", application:"04"
}


//-- Installation ----------------------------------------------------------------------------------------

def installed() {
    logging("installed() : scheduling configure() every 3 hours", 1)
    runEvery3Hours(configure)
}

def updated() {
    logging("updated() : re-scheduling configure() every 3 hours, and once within a minute.", 1)
    try {
        unschedule()
    } catch (e) {
        log.error "updated(): Error unschedule() - ${errMsg}"
    }
    runIn(60,configure)
    runEvery3Hours(configure)
    setLogsOffTask()
}

def uninstalled() {
    logging("uninstalled() : unscheduling configure()", 1)
    try {    
        unschedule()
    } catch (errMsg) {
        log.error "uninstalled(): Error unschedule() - ${errMsg}"
    }
}



// parse events into attributes
def parse(String description) {
    def result = []
    def cluster = zigbee.parse(description)
    if (description?.startsWith("read attr -")) {
        // log.info description
        def descMap = zigbee.parseDescriptionAsMap(description)
        logging("Desc Map: $descMap", 1)
        result += createCustomMap(descMap)
        if(descMap.additionalAttrs){
               def mapAdditionnalAttrs = descMap.additionalAttrs
            mapAdditionnalAttrs.each{add ->
                add.cluster = descMap.cluster
                result += createCustomMap(add)
            }
        }
    }
    
    return result
}
def refresh(){
    logging("refresh()", 1)
	def cmds = []
    cmds += zigbee.readAttribute(0x0001, 0x0021)
    cmds += zigbee.readAttribute(0x0402, 0x0000)
    cmds += zigbee.readAttribute(0x0405, 0x0000)   
    sendZigbeeCommands(cmds)
}                
def configure() {
    logging("configure()", 1)
     def cmds = []
    cmds += zigbee.configureReporting(0x0001, 0x0021, 0x20, 60, 3600, 0x01) //percentage battery
    cmds += zigbee.configureReporting(0x0402, 0x0000, 0x29, 60, 3600, 0x32) //temperature
    cmds += zigbee.configureReporting(0x0405, 0x0000, 0x29, 60, 3600, 0x32) //humidity
    sendZigbeeCommands(cmds)    
    // Submit refresh
    refresh()   
    // Return
    return
}
                
private createCustomMap(descMap) {
    def result = null
    def map = [: ]
        if (descMap.cluster == "0402" && descMap.attrId == "0000") {
            map.name = "temperature"
            map.value = getTemperature(descMap.value)
            def scale
            map.unit = "°${location.temperatureScale}"
        } else if (descMap.cluster == "0405" && descMap.attrId == "0000") {
            map.name = "humidity"
            map.value = Integer.parseInt(descMap.value, 16) / 100
            map.unit = "%"
        } else if (descMap.cluster == "0001" && descMap.attrId == "0021") {
            map.name = "battery"
            map.value = Integer.parseInt(descMap.value, 16) / 2
            map.unit = "%"
        }
        
    if (map) {
        def isChange = isStateChange(device, map.name, map.value.toString())
        map.displayed = isChange
        result = createEvent(map)
    }
    return result
}

private getTemperature(value) {
    if (value != null) {
        def celsius = Integer.parseInt(value, 16) / 100
        if (getTemperatureScale() == "C") {
            return celsius
        }
        else {
            return Math.round(celsiusToFahrenheit(celsius))
        }
    }
}


private getTemperatureScale() {
    return "${location.temperatureScale}"
}

private void logging(message, level) {    
    if (infoLogging && level == 100) {
         log.info "$message"
    }
    if (debugLogging && level == 1) {
       log.debug "$message"
    }   
}

void setLogsOffTask(boolean noLogWarning=false) {
	if (debugLogging == true) {
        runIn(1800, "logsOff")
    }
}

void logsOff() {
        log.warn "Debug logging disabled (30 minutes expired)..."
        debugLogging = false;
        device.updateSetting("debugLogging", "false")
}

private void sendZigbeeCommands(cmds) {
    cmds.removeAll { it.startsWith("delay") }
    def hubAction = new hubitat.device.HubMultiAction(cmds, hubitat.device.Protocol.ZIGBEE)
    sendHubCommand(hubAction)
}


void resetBatteryReplacedDate(boolean forced=true) {
    if(forced == true || device.currentValue('batteryLastReplaced') == null) {
        sendEvent(name: "batteryLastReplaced", value: new Date().format('yyyy-MM-dd HH:mm:ss'))
    }
}

void parseAndSendBatteryStatus(BigDecimal vCurrent) {
    BigDecimal vMin = vMinSetting == null ? 2.5 : vMinSetting
    BigDecimal vMax = vMaxSetting == null ? 3.0 : vMaxSetting
    
    BigDecimal bat = 0
    if(vMax - vMin > 0) {
        bat = ((vCurrent - vMin) / (vMax - vMin)) * 100.0
    } else {
        bat = 100
    }
    bat = bat.setScale(0, BigDecimal.ROUND_HALF_UP)
    bat = bat > 100 ? 100 : bat
    
    vCurrent = vCurrent.setScale(3, BigDecimal.ROUND_HALF_UP)

    logging("Battery event: $bat% (V = $vCurrent)", 100)
    sendEvent(name:"battery", value: bat, unit: "%", isStateChange: false)
}

void updateDataFromSimpleDescriptorData(List<String> data) {
    Map<String,String> sdi = parseSimpleDescriptorData(data)
    if(sdi != [:]) {
        updateDataValue("endpointId", sdi['endpointId'])
        updateDataValue("profileId", sdi['profileId'])
        updateDataValue("inClusters", sdi['inClusters'])
        updateDataValue("outClusters", sdi['outClusters'])
        getInfo(true, sdi)
    } else {
        log.warn("No VALID Simple Descriptor Data received!")
    }
    sdi = null
}

void getInfo(boolean ignoreMissing=false, Map<String,String> sdi = [:]) {
    log.debug("Getting info for Zigbee device...")
    String endpointId = device.getEndpointId()
    endpointId = endpointId == null ? getDataValue("endpointId") : endpointId
    String profileId = getDataValue("profileId")
    String inClusters = getDataValue("inClusters")
    String outClusters = getDataValue("outClusters")
    String model = getDataValue("model")
    String manufacturer = getDataValue("manufacturer")
    String application = getDataValue("application")
    if(sdi != [:]) {
        endpointId = endpointId == null ? sdi['endpointId'] : endpointId
        profileId = profileId == null ? sdi['profileId'] : profileId
        inClusters = inClusters == null ? sdi['inClusters'] : inClusters
        outClusters = outClusters == null ? sdi['outClusters'] : outClusters
        sdi = null
    }
    String extraFingerPrint = ""
    boolean missing = false
    String requestingFromDevice = ", requesting it from the device. If it is a sleepy device you may have to wake it up and run this command again. Run this command again to get the new fingerprint."
    if(ignoreMissing==true) {
        requestingFromDevice = ". Try again."
    }
    if(manufacturer == null) {
        missing = true
        log.warn("Manufacturer name is missing for the fingerprint$requestingFromDevice")
        if(ignoreMissing==false) sendZigbeeCommands(zigbee.readAttribute(CLUSTER_BASIC, 0x0004))
    }
    log.trace("Manufacturer: $manufacturer")
    if(model == null) {
        missing = true
        log.warn("Model name is missing for the fingerprint$requestingFromDevice")
        if(ignoreMissing==false) sendZigbeeCommands(zigbee.readAttribute(CLUSTER_BASIC, 0x0005))
    }
    log.trace("Model: $model")
    if(application == null) {
        log.info("NOT IMPORTANT: Application ID is missing for the fingerprint$requestingFromDevice")
        if(ignoreMissing==false) sendZigbeeCommands(zigbee.readAttribute(CLUSTER_BASIC, 0x0001))
    } else {
        extraFingerPrint += ", application:\"$application\""
    }
    log.trace("Application: $application")
    if(profileId == null || endpointId == null || inClusters == null || outClusters == null) {
        missing = true
        String endpointIdTemp = endpointId == null ? "01" : endpointId
        log.warn("One or multiple pieces of data needed for the fingerprint is missing$requestingFromDevice")
        if(ignoreMissing==false) sendZigbeeCommands(["he raw ${device.deviceNetworkId} 0 0 0x0004 {00 ${zigbee.swapOctets(device.deviceNetworkId)} $endpointIdTemp} {0x0000}"])
    }
    profileId = profileId == null ? "0104" : profileId
    if(missing == true) {
        log.info("INCOMPLETE - DO NOT SUBMIT THIS - TRY AGAIN: fingerprint model:\"$model\", manufacturer:\"$manufacturer\", profileId:\"$profileId\", endpointId:\"$endpointId\", inClusters:\"$inClusters\", outClusters:\"$outClusters\"" + extraFingerPrint)
    } else {
        log.info("COPY AND PASTE THIS ROW TO THE DEVELOPER: fingerprint model:\"$model\", manufacturer:\"$manufacturer\", profileId:\"$profileId\", endpointId:\"$endpointId\", inClusters:\"$inClusters\", outClusters:\"$outClusters\"" + extraFingerPrint)
    }
}

void zigbee_sonoff_parseBatteryData(Map msgMap) {
    BigDecimal bat = null
    if(msgMap["attrId"] == "0021") {
        bat = msgMap['valueParsed'] / 2.0
    } else if(msgMap.containsKey("additionalAttrs") == true) {
        msgMap["additionalAttrs"].each() {
            if(it.containsKey("attrId") == true && it['attrId'] == "0021") {
                bat = Integer.parseInt(it['value'], 16) / 2.0
            }
        }
    }
    if(bat != null) {
        bat = bat.setScale(1, BigDecimal.ROUND_HALF_UP)
        sendEvent(name:"battery", value: bat , unit: "%", isStateChange: false)
    }
}

void zigbee_sensor_parseSendTemperatureEvent(Integer rawValue, BigDecimal variance = 0.2, Integer minAllowed=-50, Integer maxAllowed=100) {
    
    List adjustedTemp = sensor_data_getAdjustedTempAlternative(rawValue / 100.0 )
    String tempUnit = adjustedTemp[0]
    BigDecimal t = adjustedTemp[1]
    BigDecimal tRaw = adjustedTemp[2]
    
    if(tRaw >= -50 && tRaw < 100) {
        BigDecimal oldT = device.currentValue('temperature') == null ? null : device.currentValue('temperature')
        if(oldT != null) oldT = oldT.setScale(1, BigDecimal.ROUND_HALF_UP)
        BigDecimal tChange = null
        if(oldT == null) {
            logging("Temperature: $t $tempUnit", 1)
        } else {
            tChange = Math.abs(t - oldT)
            tChange = tChange.setScale(1, BigDecimal.ROUND_HALF_UP)
            logging("Temperature: $t $tempUnit (old temp: $oldT, change: $tChange)", 100)
        }
        
        if(oldT == null || tChange > variance) {
            logging("Sending temperature event (Temperature: $t $tempUnit, old temp: $oldT, change: $tChange)", 100)
            sendEvent(name:"temperature", value: t, unit: "$tempUnit", isStateChange: true)
            if(reportAbsoluteHumidity == true) {
                sendAbsoluteHumidityEvent(currentTemperatureInCelsiusAlternative(t), device.currentValue('humidity'));
            }
        } else {
            logging("SKIPPING temperature event since the change wasn't large enough (Temperature: $t $tempUnit, old temp: $oldT, change: $tChange)", 100)
        }
    } else {
        log.warn "Incorrect temperature received from the sensor ($tRaw), it is probably time to change batteries!"
    }
}

void zigbee_sensor_parseSendHumidityEvent(Integer rawValue, BigDecimal variance = 0.02) {
    BigDecimal h = sensor_data_getAdjustedHumidity(rawValue / 100.0)
    BigDecimal oldH = device.currentValue('humidity')
    if(oldH != null) oldH = oldH.setScale(2, BigDecimal.ROUND_HALF_UP)
    BigDecimal hChange = null
    if(h <= 100) {
        if(oldH == null) {
            logging("Humidity: $h %", 1)
        } else {
            hChange = Math.abs(h - oldH)
            hChange = hChange.setScale(2, BigDecimal.ROUND_HALF_UP)
            logging("Humidity: $h% (old humidity: $oldH%, change: $hChange%)", 100)
        }
        
        if(oldH == null || hChange > variance) {
            logging("Sending humidity event (Humidity: $h%, old humidity: $oldH%, change: $hChange%)", 100)
            sendEvent(name:"humidity", value: h, unit: "%", isStateChange: true)
            if(reportAbsoluteHumidity == true) {
                sendAbsoluteHumidityEvent(currentTemperatureInCelsiusAlternative(), h)
            }
        } else {
            logging("SKIPPING humidity event since the change wasn't large enough (Humidity: $h%, old humidity: $oldH%, change: $hChange%)", 100)
        }
    }
}

void sendAbsoluteHumidityEvent(BigDecimal deviceTempInCelsius, BigDecimal relativeHumidity) {
    if(relativeHumidity != null && deviceTempInCelsius != null) {
        BigDecimal numerator = (6.112 * Math.exp((17.67 * deviceTempInCelsius) / (deviceTempInCelsius + 243.5)) * relativeHumidity * 2.1674) 
        BigDecimal denominator = deviceTempInCelsius + 273.15 
        BigDecimal absHumidity = numerator / denominator
        String cubeChar = String.valueOf((char)(179))
        absHumidity = absHumidity.setScale(1, BigDecimal.ROUND_HALF_UP)
        logging("Sending Absolute Humidity event (Absolute Humidity: ${absHumidity}g/m${cubeChar})", 100)
        sendEvent( name: "absoluteHumidity", value: absHumidity, unit: "g/m${cubeChar}", descriptionText: "Absolute Humidity Is ${absHumidity} g/m${cubeChar}" )
    }
}

private BigDecimal sensor_data_getAdjustedHumidity(BigDecimal value) {
    Integer res = 1
    if(humidityRes != null && humidityRes != '') {
        res = Integer.parseInt(humidityRes)
    }
    if (humidityOffset) {
	   return (value + new BigDecimal(humidityOffset)).setScale(res, BigDecimal.ROUND_HALF_UP)
	} else {
       return value.setScale(res, BigDecimal.ROUND_HALF_UP)
    }
}