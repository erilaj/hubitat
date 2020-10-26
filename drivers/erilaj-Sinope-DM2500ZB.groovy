/**
 *  Sinope Dimmer DM2500ZB Device Driver for Hubitat
 *  Source: https://github.com/erilaj/hubitat/blob/master/drivers/erilaj-Sinope-DM2500ZB.groovy
 * 
 *
 *  Code derived from Sinope's SmartThing dimmer DM2500ZB
 *  Source: https://raw.githubusercontent.com/Sinopetech/Smartthings/master/Sinope%20Technologies%20DM2500ZB%20V.1.0.0.txt
 *
 *  
 */

preferences {
	input name:"MinimalIntensityParam", tyepe: "number", title:"Light bulb minimal intensity (1..11) (default: blank)", range:"1..11", required: false
    //input("OnLedIntensityParam", "number", title:"Indicator light intensity when ON (0..100)", range:"0..100", description:"optional")
    input name: "OnLedIntensityParam", type: "number", title:"Indicator light intensity when ON (0..100)", range:"0..100", defaultValue: 50, required: true
    input name: "OffLedIntensityParam", type: "number", title:"Indicator light intensity when OFF (0..100)", range:"0..100", defaultValue: 50, required: true
	input name: "trace", type: "bool", title: "Enable Tracing", defaultValue: false
    input name: "logFilter", type: "enum", title: "Trace level", multiple: false, options: [["1":"ERROR"],["2":"WARNING"], ["3":"INFO"], ["4":"DEBUG"], ["5":"TRACE"]], defaultValue: "0", submitOnChange:true, required: false
	//input("logFilter", "number", title: "Trace level", range: "1..5",
	//	description: "1= ERROR only, 2= <1+WARNING>, 3= <2+INFO>, 4= <3+DEBUG>, 5= <4+TRACE>")
}

metadata {
    definition (name: "DM2500ZB Sinope Dimmer", namespace: "erilaj", author: "Eric Lajoie")
    {
        capability "Actuator"
        capability "Configuration"
        capability "Refresh"
        capability "Switch"
        capability "Switch Level"
        
        attribute "swBuild","string"//there are dimmers wo does not supprt the minimal intensity. theses dimmers can be identified by their swBuild under the value 106
        
        fingerprint profileId: "0104", inClusters: "0000 0003 0004 0005 0006 0008 ff01", manufacturer: "Sinope Technologies", model: "DM2500ZB"
    }

}

//-- Parsing ---------------------------------------------------------------------------------------------

def parse(String description)
{
    traceEvent(settings.logFilter, "description is $description", settings.trace, get_LOG_DEBUG())
	def cluster = zigbee.parse(description)
    def event = zigbee.getEvent(description)
    traceEvent(settings.logFilter, "EVENT = $event", settings.trace, get_LOG_DEBUG())
	
    if(event)
    {
        traceEvent(settings.logFilter, "send event : $event", settings.trace, get_LOG_DEBUG())
        sendEvent(event)
    }
    else
    {
        traceEvent(settings.logFilter, "DID NOT PARSE MESSAGE for description", settings.trace, get_LOG_WARN())
        def mymap = zigbee.parseDescriptionAsMap(description)
        if (mymap) {
              traceEvent(settings.logFilter, "Mymap is $mymap", settings.trace, get_LOG_DEBUG())
              traceEvent(settings.logFilter, "Cluster is $mymap.cluster and Attribute is $mymap.attrId", settings.trace, get_LOG_DEBUG())
              
              if(mymap.cluster == "0000" && mymap.attrId == "0001"){
              	def SwBuild
                SwBuild = mymap.value
                SwBuild = zigbee.convertHexToInt(SwBuild)
              	sendEvent(name: "swBuild", value: SwBuild)
              }
        }
    }
}

def parseDescriptionAsMap(description)
{
    traceEvent(settings.logFilter, "parsing MAP ...", settings.trace, get_LOG_DEBUG())
	(description - "read attr - ").split(",").inject([:]) 
    {
    	map, param ->
		def nameAndValue = param.split(":")
		map += [(nameAndValue[0].trim()):nameAndValue[1].trim()]
	}
}

//-- Initialisation --------------------------------------------------------------------------------------

def updated() {
    
	if (!state.updatedLastRanAt || now() >= state.updatedLastRanAt + 2000) {
		state.updatedLastRanAt = now()   
		
		return checkSettings() 
	}
	else {
        traceEvent(settings.logFilter, "updated(): Ran within last 2 seconds so aborting", settings.trace, get_LOG_TRACE())
	}
    
}

//-- On Off Control --------------------------------------------------------------------------------------

def off()
{
    zigbee.off()
}

def on()
{
    traceEvent(settings.logFilter, "sending on", settings.trace, get_LOG_DEBUG())
    zigbee.on()
}

//-- Level Control ---------------------------------------------------------------------------------------

def setLevel(value) 
{
    //traceEvent(settings.logFilter, "primary value = $value", settings.trace, get_LOG_DEBUG())
    setLevel(value,0)
}
void setLevel(level, duration) {
     duration = duration.toBigDecimal()
    def scaledDuration = (duration * 10).toInteger()
    traceEvent(settings.logFilter, "SetLevel value = $value, duration= $duration", settings.trace, get_LOG_DEBUG())
    sendZigbeeCommands(zigbee.setLevel(level, scaledDuration))
    //zigbee.setLevel(level, duration)
}

//-- refresh ---------------------------------------------------------------------------------------------

def refresh()
{
	def cmds = []
    cmds += zigbee.readAttribute(0x0006, 0x0000)
    cmds += zigbee.readAttribute(0x0008, 0x0000)
    cmds += zigbee.readAttribute(0x0000, 0x0001)							//software version
    cmds += zigbee.configureReporting(0x0006, 0x0000, 0x10, 0, 599, null)
    cmds += zigbee.configureReporting(0x0008, 0x0000, 0x20, 3, 602, 0x01)
    if(checkSoftVersion() == true){
    	cmds += zigbee.writeAttribute(0xff01, 0x0055, 0x21, getTiming((MinimalIntensityParam)?MinimalIntensityParam.toInteger():0))
    }
    return sendZigbeeCommands(cmds)
}

//-- configuration ---------------------------------------------------------------------------------------

def configure()
{
    log.debug "Configuring Reporting and Bindings."
    return  zigbee.configureReporting(0x0006, 0x0000, 0x10, 0, 599, null) +
            zigbee.configureReporting(0x0008, 0x0000, 0x20, 3, 602, 0x01) +
            zigbee.readAttribute(0x0006, 0x0000) +
            zigbee.readAttribute(0x0008, 0x0000) +
            zigbee.readAttribute(0x0000, 0x0001)
            
}

//-- Check Settings ---------------------------------------------------------------------------------------

private void checkSettings()
{
	def cmds = []
	if(checkSoftVersion() == true)
    {
    	def MinLight = (MinimalIntensityParam)?MinimalIntensityParam.toInteger():0
   			def Timing = getTiming(MinLight)
            traceEvent(settings.logFilter, "Timing to: $Timing", settings.trace, get_LOG_DEBUG())
        	
        	cmds += zigbee.writeAttribute(0xff01, 0x0055, 0x21, Timing)
			
    }
    
    if(OnLedIntensityParam){
        traceEvent(settings.logFilter, "ON Led Intensity: ${OnLedIntensityParam}", settings.trace, get_LOG_INFO())
    	cmds += zigbee.writeAttribute(0xff01, 0x0052, 0x20, OnLedIntensityParam.toInteger())//MaxIntensity On
        try{
             traceEvent(settings.logFilter, "OFF Led Intensity: ${OffLedIntensityParam}", settings.trace, get_LOG_INFO())
            cmds += zigbee.writeAttribute(0xff01, 0x0053, 0x20, OffLedIntensityParam.toInteger())//MaxIntensity Off
        }
        catch(errMsg){
            traceEvent(settings.logFilter, "Error OffLed - ${errMsg}", settings.trace, get_LOG_INFO())
        }
    	//cmds += zigbee.writeAttribute(0xff01, 0x0053, 0x20, LedIntensityParam)//MaxIntensity Off
    }
    else{
        traceEvent(settings.logFilter, "Default ON Led Intensity: 50", settings.trace, get_LOG_INFO())
        cmds += zigbee.writeAttribute(0xff01, 0x0052, 0x20, 50)//MaxIntensity On    	
    }
    //if(OffLedIntensityParam){
        //traceEvent(settings.logFilter, "OFF Led Intensity: ${OffLedIntensityParam}", settings.trace, get_LOG_INFO())
        //cmds += zigbee.writeAttribute(0xff01, 0x0053, 0x20, OffLedIntensityParam)//MaxIntensity Off
    //}
    //else{
    //     traceEvent(settings.logFilter, "Default OFF Led Intensity: 50", settings.trace, get_LOG_INFO())
    //    cmds += zigbee.writeAttribute(0xff01, 0x0053, 0x20, 50)//MaxIntensity Off
    //}
        
    sendZigbeeCommands(cmds)
}

private int getTiming(def setting)
{
	def Timing
    	switch(setting)
    	{
    	case(1):
       		Timing = 100
       		break;
    	case(2):
       		Timing = 250
    		break;    
    	case(3):
       		Timing = 500
    		break;
    	case(4):
       		Timing = 750
    		break;
    	case(5):
       		Timing = 1000
    		break;
    	case(6):
       		Timing = 1250
    		break;
    	case(7):
       		Timing = 1500
    		break;
    	case(8):
       		Timing = 1750
    		break;
    	case(9):
       		Timing = 2000
    		break;
    	case(10):
       		Timing = 2250
    		break;
        case(11):
       		Timing = 1400
    		break;
    	default:
       		Timing = 600
       		break;
    	}
        return Timing
}

private void sendZigbeeCommands(cmds, delay = 1000) {
	cmds.removeAll { it.startsWith("delay") }
	// convert each command into a HubAction
	//cmds = cmds.collect { new physicalgraph.device.HubAction(it) }
    def hubAction = new hubitat.device.HubMultiAction(cmds, hubitat.device.Protocol.ZIGBEE)
    sendHubCommand(hubAction)
	//sendHubCommand(cmds, delay)
}

private boolean checkSoftVersion()
{
	def version
    def versionMin = "106"
    def Build = device.currentState("swBuild")?.value
    traceEvent(settings.logFilter, "soft version: $Build", settings.trace, get_LOG_DEBUG())
    
    if(Build > versionMin)//if the version is under 107, the minimal light intensity is not supported.
    {
        traceEvent(settings.logFilter, "intensity supported", settings.trace, get_LOG_DEBUG())
    	version = true
    }
    else
    {
        traceEvent(settings.logFilter, "intensity not supported", settings.trace, get_LOG_DEBUG())
        version = false
    }
    return version
}

private int get_LOG_ERROR() {
	return 1
}
private int get_LOG_WARN() {
	return 2
}
private int get_LOG_INFO() {
	return 3
}
private int get_LOG_DEBUG() {
	return 4
}
private int get_LOG_TRACE() {
	return 5
}

def traceEvent(logFilter, message, displayEvent = false, traceLevel = 4, sendMessage = true) {
	int LOG_ERROR = get_LOG_ERROR()
	int LOG_WARN = get_LOG_WARN()
	int LOG_INFO = get_LOG_INFO()
	int LOG_DEBUG = get_LOG_DEBUG()
	int LOG_TRACE = get_LOG_TRACE()
	int filterLevel = (logFilter) ? logFilter.toInteger() : get_LOG_WARN()
    
	if ((displayEvent) || (sendMessage)) {
		def results = [
			name: "verboseTrace",
			value: message,
			displayed: ((displayEvent) ?: false)
		]

		if ((displayEvent) && (filterLevel >= traceLevel)) {
			switch (traceLevel) {
				case LOG_ERROR:
					log.error "${message}"
					break
				case LOG_WARN:
					log.warn "${message}"
					break
				case LOG_INFO:
					log.info "${message}"
					break
				case LOG_TRACE:
					log.trace "${message}"
					break
				case LOG_DEBUG:
				default:
					log.debug "${message}"
					break
			} /* end switch*/
			if (sendMessage) sendEvent(results)
		} /* end if displayEvent*/
	}
}
