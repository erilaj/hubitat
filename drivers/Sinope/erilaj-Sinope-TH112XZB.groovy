/**
 *  Sinope TH1123ZB, TH1124ZB Device Driver for Hubitat
 *
 *  Code derived from kris2k2
 *  Source: https://github.com/kris2k2/hubitat/drivers/kris2k2-Sinope-TH112XZB.groovy
 * 
 * 
 */

metadata {

    definition(name: "Sinope TH112XZB Thermostat", namespace: "erilaj", author: "Eric Lajoie") {
        // https://docs.hubitat.com/index.php?title=Driver_Capability_List#Thermostat
        //
        capability "Configuration"
        capability "TemperatureMeasurement"
        capability "Thermostat"
        capability "Refresh"
        capability "PowerMeter"
        
        // Receiving temperature notifications via RuleEngine
        capability "Notification"
        
        attribute "outdoorTemp", "string"
        
        command "eco"
        command "displayOn"
        command "displayOff"
        command "setClockTime"
        
        preferences {
            input name: "prefDisplayOutdoorTemp", type: "bool", title: "Enable Display of Outdoor Temperature", defaultValue: true
            input name: "prefTimeFormatParam", type: "enum", title: "Time Format", options:[["1":"24h"], ["2":"12h AM/PM"]], defaultValue: "1", multiple: false, required: true
            input name: "prefBacklightMode", type: "enum", title: "Backlight Mode", multiple: false, options: [["1":"Always ON"],["2":"On Demand"], ["3":"Custom Command"]], defaultValue: "1", submitOnChange:true, required: true
            input name: "prefKeyLock", type: "bool", title: "Enable Keylock", defaultValue: false
            input name: "prefLogging", type: "bool", title: "Enable Logging", defaultValue: false
        }        

        fingerprint profileId: "0104", deviceId: "119C", manufacturer: "Sinope Technologies", model: "TH1123ZB", deviceJoinName: "TH1123ZB"
        fingerprint profileId: "0104", deviceId: "119C", manufacturer: "Sinope Technologies", model: "TH1124ZB", deviceJoinName: "TH1124ZB"
    }
}

//-- Installation ----------------------------------------------------------------------------------------

def installed() {
    if(prefLogging) log.info "installed() : scheduling configure() every 3 hours"
    //state.hideClock = prefHideClock
    runEvery3Hours(configure)
}

def updated() {
    if(prefLogging) log.info "updated() : re-scheduling configure() every 3 hours, and once within a minute."
    try {
        unschedule()
    } catch (e) {
        if(prefLogging) log.error "updated(): Error unschedule() - ${errMsg}"
    }

    
    //state.hideClock = prefHideClock
    runIn(1,configure)
    runEvery3Hours(configure)
    try{
        state.remove("displayClock")
        state.remove("hideClock")
    }catch(errMsg){
         if(prefLogging) log.error "${errMsg}"
    }
}

def uninstalled() {
    if(prefLogging) log.info "uninstalled() : unscheduling configure()"
    try {    
        unschedule()
    } catch (errMsg) {
        if(prefLogging) log.error "uninstalled(): Error unschedule() - ${errMsg}"
    }
}


//-- Parsing ---------------------------------------------------------------------------------------------

// parse events into attributes
def parse(String description) {
    def result = []
    def scale = getTemperatureScale()
    state?.scale = scale
    def cluster = zigbee.parse(description)
    if (description?.startsWith("read attr -")) {
        // log.info description
        def descMap = zigbee.parseDescriptionAsMap(description)
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

private createCustomMap(descMap){
    def result = null
    def map = [: ]
        if (descMap.cluster == "0201" && descMap.attrId == "0000") {
            map.name = "temperature"
            map.value = getTemperature(descMap.value)
            map.unit = "°${location.temperatureScale}"
            
        } else if (descMap.cluster == "0201" && descMap.attrId == "0008") {
            map.name = "thermostatOperatingState"
            map.value = getHeatingDemand(descMap.value)
            map.value = (map.value.toInteger() < 10) ? "idle" : "heating"
        
        } else if (descMap.cluster == "0201" && descMap.attrId == "0012") {
            map.name = "heatingSetpoint"
            map.value = getTemperature(descMap.value)
            map.unit = "°${location.temperatureScale}"
            if(prefLogging) log.info "heatingSetpoint: ${map.value}"
            
        } else if (descMap.cluster == "0201" && descMap.attrId == "0015") {
            map.name = "heatingSetpointRangeLow"
            map.value = getTemperature(descMap.value)

        } else if (descMap.cluster == "0201" && descMap.attrId == "0016") {
            map.name = "heatingSetpointRangeHigh"
            map.value = getTemperature(descMap.value)
            
        } else if (descMap.cluster == "0201" && descMap.attrId == "001C") {
            map.name = "thermostatMode"
            map.value = getModeMap()[descMap.value]
            
        } else if (descMap.cluster == "0204" && descMap.attrId == "0001") {
            map.name = "thermostatLock"
            map.value = getLockMap()[descMap.value]
            
        } else if (descMap.cluster == "0B04" && descMap.attrId == "050B") {
             //map.name = "thermostatPower"
            map.name = "power"
            map.value = getActivePower(descMap.value)
            map.unit = "W"
        }
        
    if (map) {
        def isChange = isStateChange(device, map.name, map.value.toString())
        map.displayed = isChange
        if ((map.name.toLowerCase().contains("temp")) || (map.name.toLowerCase().contains("setpoint"))) {
            map.scale = scale
        }
        // log.info "map: ${map}"
        result = createEvent(map)
    }
    return result
}

//-- Capabilities

def refresh() {
    if(prefLogging) log.info "refresh()"
    def cmds = []    
    cmds += zigbee.readAttribute(0x0201, 0x0000) //Read Local Temperature
    cmds += zigbee.readAttribute(0x0201, 0x0008) //Read PI Heating State  
    cmds += zigbee.readAttribute(0x0201, 0x0012) //Read Heat Setpoint
    cmds += zigbee.readAttribute(0x0201, 0x001C) //Read System Mode
    cmds += zigbee.readAttribute(0x0201, 0x401C, [mfgCode: "0x1185"]) //Read System Mode
    
    cmds += zigbee.readAttribute(0x0204, 0x0000) //Read Temperature Display Mode
    cmds += zigbee.readAttribute(0x0204, 0x0001) //Read Keypad Lockout
    
    cmds += zigbee.readAttribute(0x0B04, 0x050B)  //Read thermostat Active power
    
    // Submit zigbee commands
    sendZigbeeCommands(cmds)
    
    setClockTime()
}   

def configure(){    
    if(prefLogging) log.info "configure()"
        
    // Set unused default values
    sendEvent(name: "coolingSetpoint", value:getTemperature("0BB8")) // 0x0BB8 =  30 Celsius
    sendEvent(name: "thermostatFanMode", value:"auto") // We dont have a fan, so auto is is
    updateDataValue("lastRunningMode", "heat") // heat is the only compatible mode for this device

    // Prepare our zigbee commands
    def cmds = []

    // Configure Reporting
    cmds += zigbee.configureReporting(0x0201, 0x0000, 0x29, 30, 301, 50)     //local temperature
    cmds += zigbee.configureReporting(0x0201, 0x0008, 0x0020, 4, 300, 10)    //PI heating demand
    cmds += zigbee.configureReporting(0x0201, 0x0012, 0x0029, 0, 302, 50)     //occupied heating setpoint    
    cmds += zigbee.configureReporting(0x0204, 0x0000, 0x30, 1, 0)           //Attribute ID 0x0000 = temperature display mode, Data Type: 8 bits enum
    cmds += zigbee.configureReporting(0x0204, 0x0001, 0x30, 1, 0)           //Attribute ID 0x0001 = keypad lockout, Data Type: 8 bits enum
    cmds += zigbee.configureReporting(0x0B04, 0x050B, DataType.INT16, 30, 599, 0x64) //Thermostat power draw
    
    // Configure displayed scale
    if (getTemperatureScale() == 'C') {
        cmds += zigbee.writeAttribute(0x0204, 0x0000, 0x30, 0)    // Wr °C on thermostat display
    } else {
        cmds += zigbee.writeAttribute(0x0204, 0x0000, 0x30, 1)    // Wr °F on thermostat display 
    }

    // Configure keylock
    if (prefKeyLock) {
        cmds+= zigbee.writeAttribute(0x204, 0x01, 0x30, 0x01) // Lock Keys
    } else {
        cmds+= zigbee.writeAttribute(0x204, 0x01, 0x30, 0x00) // Unlock Keys
    }

    // Configure Outdoor Weather
    if (prefDisplayOutdoorTemp) {
        cmds += zigbee.writeAttribute(0xFF01, 0x0011, 0x21, 10800)  //set the outdoor temperature timeout to 3 hours
    } else {
        cmds += zigbee.writeAttribute(0xFF01, 0x0011, 0x21, 0)  //set the outdoor temperature timeout immediately
    }     
        
    // Configure Screen Brightness
    //if(prefDisplayBacklight){
    //    cmds += zigbee.writeAttribute(0x0201, 0x0402, 0x30, 0x0001) // set display brigtness to explicitly on       
    //} else {
    //   cmds += zigbee.writeAttribute(0x0201, 0x0402, 0x30, 0x0000) // set display brightnes to ambient lighting
    //}
    if(prefBacklightMode == "1"){
         cmds += zigbee.writeAttribute(0x0201, 0x0402, 0x30, 0x0001) // set display brigtness to explicitly on
    }else if(prefBacklightMode == "2"){
        cmds += zigbee.writeAttribute(0x0201, 0x0402, 0x30, 0x0000) // set display brightness to ambient lighting
    }
       
     //Configure Clock Format
    if(prefTimeFormatParam == "2"){//12h AM/PM
       if(prefLogging) log.info "Set to 12h AM/PM"
        cmds += zigbee.writeAttribute(0xFF01, 0x0114, 0x30, 0x0001)
    }
    else{//24h
        if(prefLogging) log.info "Set to 24h"
        cmds += zigbee.writeAttribute(0xFF01, 0x0114, 0x30, 0x0000)
    }

    // Submit zigbee commands
    sendZigbeeCommands(cmds)    
    // Submit refresh
    refresh()   
    // Return
    return
}

def auto() {
    if(prefLogging) log.info "auto(): mode is not available for this device. => Defaulting to heat mode instead."
    heat()
}

def cool() {
    if(prefLogging) log.info "cool(): mode is not available for this device. => Defaulting to eco mode instead."
    eco()
}

def emergencyHeat() {
    if(prefLogging) log.info "emergencyHeat(): mode is not available for this device. => Defaulting to heat mode instead."
    heat()
}

def fanAuto() {
    if(prefLogging) log.info "fanAuto(): mode is not available for this device"
}

def fanCirculate() {
    if(prefLogging) log.info "fanCirculate(): mode is not available for this device"
}

def fanOn() {
    if(prefLogging) log.info "fanOn(): mode is not available for this device"
}

def heat() {
    if(prefLogging) log.info "heat(): mode set"
    
    def cmds = []
    cmds += zigbee.writeAttribute(0x0201, 0x001C, 0x30, 04, [:], 1000) // MODE
    cmds += zigbee.writeAttribute(0x0201, 0x401C, 0x30, 04, [mfgCode: "0x1185"]) // SETPOINT MODE
    sendEvent("name": "thermostatMode", "value": "heat")
    // Submit zigbee commands
    sendZigbeeCommands(cmds)
}

def off() {
    if(prefLogging) log.info "off(): mode set"
    
    def cmds = []
    cmds += zigbee.writeAttribute(0x0201, 0x001C, 0x30, 0)
     sendEvent("name": "thermostatMode", "value": "off")
    // Submit zigbee commands
    sendZigbeeCommands(cmds)    
}

def setCoolingSetpoint(degrees) {
    if(prefLogging) log.info "setCoolingSetpoint(${degrees}): is not available for this device"
}

def setHeatingSetpoint(preciseDegrees) {
    if(prefLogging) log.info "setHeatingSetpoint(${preciseDegrees})"
    if (preciseDegrees != null) {
        def temperatureScale = getTemperatureScale()
        def degrees = new BigDecimal(preciseDegrees).setScale(1, BigDecimal.ROUND_HALF_UP)
        def cmds = []        
        
        if(prefLogging) log.info "setHeatingSetpoint(${degrees}:${temperatureScale})"
        
        def celsius = (temperatureScale == "C") ? degrees as Float : (fahrenheitToCelsius(degrees) as Float).round(2)
        int celsius100 = Math.round(celsius * 100)
      
        cmds += zigbee.writeAttribute(0x0201, 0x0012, 0x29, celsius100) //Write Heat Setpoint

        //send event for thermostatSetpoint
         def tempValueString = (temperatureScale == "C") ? String.format('%2.1f', degrees) :  String.format('%2d', degrees.intValue())
        sendEvent("name": "heatingSetpoint", "value": tempValueString)
        sendEvent("name": "thermostatSetpoint", "value": tempValueString)

        // Submit zigbee commands
        sendZigbeeCommands(cmds)         
    } 
}

def setSchedule(JSON_OBJECT){
    if(prefLogging) log.info "setSchedule(JSON_OBJECT): is not available for this device"
}

def setThermostatFanMode(fanmode){
    if(prefLogging) log.info "setThermostatFanMode(${fanmode}): is not available for this device"
}

def setThermostatMode(String value) {
    if(prefLogging) log.info "setThermostatMode(${value})"
    def currentMode = device.currentState("thermostatMode")?.value
    def lastTriedMode = state.lastTriedMode ?: currentMode ?: "heat"
    def modeNumber;
    Integer setpointModeNumber;
    def modeToSendInString;
    switch (value) {
        case "heat":
        case "emergency heat":
        case "auto":
            return heat()
        
        case "eco":
        case "cool":
            return eco()
        
        default:
            return off()
    }
}

def eco() {
    if(prefLogging) log.info "eco()"
    
    def cmds = []
    cmds += zigbee.writeAttribute(0x0201, 0x001C, 0x30, 04, [:], 1000) // MODE
    cmds += zigbee.writeAttribute(0x0201, 0x401C, 0x30, 05, [mfgCode: "0x1185"]) // SETPOINT MODE    
    
    // Submit zigbee commands
    sendZigbeeCommands(cmds)   
}

def deviceNotification(text) {
   
        double outdoorTemp = text.toDouble()
        def cmds = []

        if (prefDisplayOutdoorTemp) {
            if(prefLogging) log.info "deviceNotification() : Received outdoor weather : ${text} : ${outdoorTemp}"
    
            //the value sent to the thermostat must be in C
            if (getTemperatureScale() == 'F') {    
                outdoorTemp = fahrenheitToCelsius(outdoorTemp).toDouble()
        }        
        sendEvent( name: "outdoorTemp", value: outdoorTemp, unit: state?.scale)
        int outdoorTempDevice = outdoorTemp*100
        cmds += zigbee.writeAttribute(0xFF01, 0x0011, 0x21, 10800)   //set the outdoor temperature timeout to 3 hours
        cmds += zigbee.writeAttribute(0xFF01, 0x0010, 0x29, outdoorTempDevice, [mfgCode: "0x119C"]) //set the outdoor temperature as integer
    
        // Submit zigbee commands    
        sendZigbeeCommands(cmds)
        } else {
            if(prefLogging) log.info "deviceNotification() : Not setting any outdoor weather, since feature is disabled."  
        }
}
def displayOn(){
    if(prefLogging) log.info "displayOn() command send"
    def cmds = []
    cmds += zigbee.writeAttribute(0x0201, 0x0402, 0x30, 0x0001) // set display brigtness to explicitly on 
    // Submit zigbee commands    
    sendZigbeeCommands(cmds)
}
def displayOff(){
    if(prefLogging) log.info "displayOff() command send"
    def cmds = []
     cmds += zigbee.writeAttribute(0x0201, 0x0402, 0x30, 0x0000) // set display brightnes to ambient lighting
     // Submit zigbee commands    
     sendZigbeeCommands(cmds)
}

def setClockTime(){   
    if(prefLogging) log.info "setClockTime() command send"
     def cmds =[]
      def thermostatDate = new Date();
      def thermostatTimeSec = thermostatDate.getTime() / 1000;
      def thermostatTimezoneOffsetSec = thermostatDate.getTimezoneOffset() * 60;
      def currentTimeToDisplay = Math.round(thermostatTimeSec - thermostatTimezoneOffsetSec - 946684800);
      cmds += zigbee.writeAttribute(0xFF01, 0x0020, DataType.UINT32, zigbee.convertHexToInt(hex(currentTimeToDisplay)), [mfgCode: "0x119C"])
      sendZigbeeCommands(cmds)
}
//-- Private functions -----------------------------------------------------------------------------------
private void sendZigbeeCommands(cmds) {
    cmds.removeAll { it.startsWith("delay") }
    def hubAction = new hubitat.device.HubMultiAction(cmds, hubitat.device.Protocol.ZIGBEE)
    sendHubCommand(hubAction)
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

private getModeMap() {
  [
    "00": "off",
    "04": "heat"
  ]
}

private getLockMap() {
  [
    "00": "unlocked ",
    "01": "locked ",
  ]
}

private getActivePower(value) {
  if (value != null)
  {
    def activePower = Integer.parseInt(value, 16)

    return activePower
  }
}

private getTemperatureScale() {
    return "${location.temperatureScale}"
}

private getHeatingDemand(value) {
    if (value != null) {
        def demand = Integer.parseInt(value, 16)
        return demand.toString()
    }
}
private hex(value) {

	String hex=new BigInteger(Math.round(value).toString()).toString(16)
	return hex    
}
private String swapEndianHex(String hex) {
	reverseArray(hex.decodeHex()).encodeHex()
}

private byte[] reverseArray(byte[] array) {
	int i = 0;
	int j = array.length - 1;
	byte tmp;

	while (j > i) {
		tmp = array[j];
		array[j] = array[i];
		array[i] = tmp;
		j--;
		i++;
	}

	return array
}
