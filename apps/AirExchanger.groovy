definition(
    name: "Air Exchanger Control",
    namespace: "erilaj",
    author: "Eric Lajoie",
    description: "Control for an air exchanger",
    category: "Convenience",
    iconUrl: "",
    iconX2Url: "")

preferences {
	page(name: "mainPage")
}

def mainPage() {
	dynamicPage(name: "mainPage", title: "Air Exchanger", install: true, uninstall: true) {
		section {
			input "thisName", "text", title: "Name this app", submitOnChange: true
			if(thisName) app.updateLabel("$thisName")
            section('Sensors'){
			    input "outdoorTemperature", "capability.temperatureMeasurement", title: "Select Outdoor Temperature Sensor", submitOnChange: true, required: true, multiple: false
                input "outdoorHumidity", "capability.relativeHumidityMeasurement", title: "Select Outdoor Humidity Sensor", submitOnChange: true, required: true, multiple: false
                input "indoorHumidity", "capability.relativeHumidityMeasurement", title: "Select Indoor Humidity Sensor", submitOnChange: true, required: true, multiple: false
            }
            section('Control Switches'){
                input "circulateMode", "capability.switch", title: "Select a switch for the Circulation Mode", submitOnChange: true, required: true, multiple: false
                input "exchangeMode", "capability.switch", title: "Select a switch for the Exchange Mode", submitOnChange: true, required: true, multiple: false
            }
            section('Parameters'){
                input "exchangeRate", "number", title: "Default Exchange Rate (min/hour)",range:"0..60", defaultvalue:15, submitOnChange: true, required: true, multiple: false
                input "hysteresis", "decimal", title: "Hysteresis for the humidity",range:"0..5", defaultvalue:0, submitOnChange: true, required: true, multiple: false
            }
            section('Disable Switch'){
                input "disableSwitch", "capability.switch", title: "Select a switch to disable the automation", submitOnChange: true, required: true, multiple: false
            }
		}
	}
}

def installed() {
    log.info "installed()"
	initialize()

}

def updated() {
    log.info "updated()"
	unsubscribe()
	initialize()
}

def initialize() {
    log.info "initialize()"
	subscribe(outdoorTemperature, "temperature", outdoorTempHandler)
    subscribe(outdoorHumidity, "humidity", outdoorHumidityHandler)
    subscribe(indoorHumidity, "humidity", indoorHumidityHandler)
    subscribe(disableSwitch, "switch", disableSwitchHandler)
    state.outdoorTemp = outdoorTemperature.currentTemperature
    state.IsExchangeMode = false
    state.disableSwitch = false
    runEvery1Hour(SheduleMode)
}

def outdoorTempHandler(evt) {
	state.outdoorTemp = evt.value.toInteger()
	EvaluateCondition()
	//log.info "OutdoorTemp = $state.outdoorTemp"
}

def outdoorHumidityHandler(evt) {
	state.outdoorHumidity = evt.value.toInteger()
    EvaluateCondition()
	//log.info "OutdoorHumidity = $state.outdoorHumidity"
}

def indoorHumidityHandler(evt) {
	state.indoorHumidity = evt.value.toDouble()
    EvaluateCondition()
	//log.info "IndoorHumidity = $state.indoorHumidity"
}

def disableSwitchHandler(evt){
    state.disableSwitch = evt.value == "on" ? true : false
    log.info("disableSwitch state is $evt.value")
    if(state.disableSwitch){
        log.info("Automation turned off because disableSwitch is ON")
        exchangeMode.off()
        circulateMode.off()
    }
   
}

def SheduleMode(){
    if(!state.disableSwitch && !state.IsExchangeMode){
        log.info "Air Exchange turned ON (Sheduled)"
        exchangeMode.on()
        def offtime = exchangeRate*60
        runIn(offtime, OffExchangeSched)
    }
}

def OffExchangeSched(){
    if(!state.IsExchangeMode){
        log.info "Air Exchange turned OFF (Sheduled)"
        exchangeMode.off()
    }
}

def EvaluateCondition(){
	def targetHum = GetHumidityTarget(state.outdoorTemp)
    if(!state.disableSwitch && !state.IsExchangeMode && state.indoorHumidity > targetHum + hysteresis && state.outdoorHumidity <= targetHum){
        state.IsExchangeMode = true
        log.info "Air Exchange turned ON (Base on humidity)"
        exchangeMode.on()
    }
    else if(state.IsExchangeMode && (state.indoorHumidity <= targetHum - hysteresis || state.outdoorHumidity > targetHum) ){
        state.IsExchangeMode = false
        log.info "Air Exchange turned OFF (Base on humidity)"
        exchangeMode.off()        
    }
	log.info("HumidityTarget = $targetHum ; IndoorHumidity = $state.indoorHumidity ; OutdoorHumidity = $state.outdoorHumidity ; OutdoorTemp = $state.outdoorTemp ; ExchangeMode= $state.IsExchangeMode ; Hysteresis= $hysteresis")
}

def GetHumidityTarget(def temp){
    if(temp > 10 && temp <= 20){
		return 55
	}
	else if(temp > 0 && temp <= 10){
		return 50
	}
	else if(temp > -10 && temp <= 0){
		return 40
	}
	else if(temp > -20 && temp <= -10){
		return 35
	}
	else if(temp > -40 && temp <= -20){
		return 30
	}
    else{
        return 65
    }
//20°C à 10°C = 55%
//10°C à 0°C = 50%
//0°C à -10°C = 40%
//-10°C à -20°C = 35%
//-20°C à -30°C = 30%
}