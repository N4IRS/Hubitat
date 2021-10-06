/**
 * Esensors HVAC Monitor Model EM08 Sensor
 *
 * Based on HTTP Presence Sensor by Joel Wetzel
 *
 *  Copyright 2019 Steve Zingman
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
 */

	
metadata {
	definition (name: "EM08 Environment Sensor", namespace: "szingman", author: "Steve Zingman") {
		capability "Refresh"
		capability "Sensor"
        //capability "Presence Sensor"
        
        capability "Temperature Measurement"
        capability "RelativeHumidityMeasurement"
        capability "Illuminance Measurement"
    }

	preferences {
		section {
			input (
				type: "string",
				name: "endpointUrl",
				title: "Endpoint URL",
				required: true				
			)
            input (
				type: "string",
				name: "path",
				title: "Path",
				required: true				
			)
			input (
				type: "bool",
				name: "enableDebugLogging",
				title: "Enable Debug Logging?",
				required: true,
				defaultValue: false
			)
		}
	}
}

def log(msg) {
	if (enableDebugLogging) {
		log.debug msg
	}
}


def installed () {
	log.info "${device.displayName}.installed()"
    updated()
}


def updated () {
	log.info "${device.displayName}.updated()"
    
    state.tryCount = 0
    
    runEvery1Minute(refresh)
    runIn(2, refresh)
}


def refresh() {
	def params = [
		uri: endpointUrl,
		path: path
	]

log "${device.displayName}.refresh()"

	state.tryCount = state.tryCount + 1
    
    if (state.tryCount > 3 && device.currentValue('presence') != "not present") {
        def descriptionText = "${device.displayName} is OFFLINE";
        log descriptionText
        sendEvent(name: "presence", value: "not present", linkText: deviceName, descriptionText: descriptionText)
    }
	asynchttpGet("httpGetCallback", params);
    }

def httpGetCallback(response, data) {
    // environment = new XmlSlurper().parse("http://192.168.190.10/status.xml")
    environment = new XmlSlurper().parse("$endpointUrl$path")    //Ugly but working    
    if (response == null || response.class != hubitat.scheduling.AsyncResponse) {
		return
	}
		
	if (response.getStatus() == 200) {
		state.tryCount = 0
            log.debug "${device.displayName}: Temperature = $environment.tm0"  
            log.debug "${device.displayName}: Humidity = $environment.hu0"
            log.debug "${device.displayName}: Illumination = $environment.il0"
		
		if (device.currentValue('presence') != "present") {
			def descriptionText = "${device.displayName} is ONLINE";
            log descriptionText
			//sendEvent(name: "presence", value: "present", linkText: deviceName, descriptionText: descriptionText)
            sendEvent(name: "temperature", value: "$environment.tm0", linkText: deviceName, descriptionText: descriptionText)
            sendEvent(name: "humidity", value: "$environment.hu0", linkText: deviceName, descriptionText: descriptionText)
            sendEvent(name: "illuminance", value: "$environment.il0", linkText: deviceName, descriptionText: descriptionText)
		}
	}
}
