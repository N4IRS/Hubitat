/**
 *
 *  Tuya Blitzwolf ZigBee Leak Detector
 *  Device Driver for Hubitat Elevation hub
 *  Version 1.0
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
 *  Based on:
 *  Konke ZigBee Motion Sensor
 *  Device Driver for Hubitat Elevation hub
 *  Version 1.0.2
 *  https://github.com/muxa/hubitat/blob/master/drivers/konke-zigbee-motion-sensor.groovy
 *  Based on code from Robert Morris,ssalahi and muxa.
 */

import hubitat.zigbee.clusters.iaszone.ZoneStatus
import hubitat.zigbee.zcl.DataType

metadata {
	definition (name: "Tuya ZigBee TS0207 Leak Detector", namespace: "n4irs", author: "Steve N4IRS") {
        capability "Battery"
        capability "Configuration"
        capability "Refresh"
        capability "Sensor"
        capability "Water Sensor"

        command "wet"
        command "dry"
        
        attribute "batteryLevelLastReceived", "string"
                        
        fingerprint endpointId: "01", profileId: "0104", inClusters: "0000,0001,0003,0500", outClusters: "0003 0019", manufacturer: "_TYZB01_sqmd19i1", model: "TS0207", application:"43"
	    }

	preferences {
		input "batteryReportingHours", "number", title: "Report battery every ___ hours. Default = 12h (Minimum 2 h)", description: "", range: "2..12", defaultValue: 12
		input name: "infoLogging", type: "bool", title: "Enable info message logging", description: ""
		input name: "debugLogging", type: "bool", title: "Enable debug message logging", description: ""
		input name: "batteryvoltage", type: "bool", title: "Enable battery voltage event reporting", description: ""
		input name: "batteryavailable", type: "bool", title: "Enable battery available event reporting", description: ""
        }
}

// Parse incoming device messages to generate events
def parse(String description) {
        // logDebug ("Incoming data from device: $description")
    Map map = [:]
    // logDebug("Parsing: $description")
	if (description?.startsWith('zone status')) {	
          // logDebug("Zone status: $description")
        def zs = zigbee.parseZoneStatus(description)
          // logDebug("Zone status: $zs")
        map = parseIasMessage(zs)
    }
    else if (description?.startsWith("catchall") || description?.startsWith("read attr"))
    {
        Map descMap = zigbee.parseDescriptionAsMap(description)        
        if (descMap.clusterInt == zigbee.POWER_CONFIGURATION_CLUSTER && descMap.attrInt == 0x0020) {
            map = parseBattery(descMap.value)
        } else if (descMap.command == "07") {
            // Process "Configure Reporting" response            
            if (descMap.data[0] == "00") {
                switch (descMap.clusterInt) {
                    case zigbee.POWER_CONFIGURATION_CLUSTER:
                        logInfo("Battery reporting configured");                        
                        break
                    default:                    
                        log.warn("Unknown reporting configured: ${descMap}");
                        break
                }
            } else {
                log.warn "Reporting configuration failed: ${descMap}"
            }
        } else if (descMap.clusterInt == 0x0500 && descMap.attrInt == 0x0002) {
            // logDebug("Zone status reported: $descMap")
            def zs = new ZoneStatus(Integer.parseInt(descMap.value, 16))
            map = parseIasMessage(zs)        
        } else if (descMap.clusterInt == 0x0500 && descMap.attrInt == 0x0011) {
            logInfo("IAS Zone ID: ${descMap.value}")
        } else if (descMap.profileId == "0000") {
        
            // ignore routing table messages
        } else {
            log.warn ("Description map not parsed: $descMap")            
        }
    }
    //------IAS Zone Enroll request------//
	else if (description?.startsWith('enroll request')) {
		logInfo "Sending IAS enroll response..."
		return zigbee.enrollResponse()
	}
      else {
        log.warn "Description not parsed: $description"
    }
    
    if (map != [:]) {
		// logInfo(map.descriptionText)
        logDebug(description)
		return createEvent(map)
	} else
		return [:]
}

// helpers -------------------

def parseIasMessage(ZoneStatus zs) {
    if (zs.alarm1 == 1 && zs.battery == 0 && zs.trouble == 0) {
            logDebug "zs.alarm1 = $zs.alarm1"
            sendEvent(name:'water', value:'wet', isStateChange: true, descriptionText: "$device.displayName is wet", type: "Digital")
            // sendEvent(name: "water", value: "wet", descriptionText: "$device.displayName is wet", type: "physical")
            // "isStateChange: true" has got the logging of the sensor's state, even though it has not changed since last time logged.
    }

    else if (zs.battery == 0 && zs.trouble == 0 && zs.alarm1 == 0) {
            logDebug "zs.alarm1 = $zs.alarm1"
            sendEvent(name:'water', value:'dry', isStateChange: true, descriptionText: "$device.displayName is dry", type: "Digital")
    }

    else {
            log.warn "Zone status message not parsed"
    }
}

def wet() {
    sendEvent(name: "water", value: "wet", descriptionText: "$device.displayName manually set to wet", isStateChange: true, type: "UI")
}

def dry() {
    sendEvent(name: "water", value: "dry",descriptionText: "$device.displayName manually set to dry", isStateChange: true, type: "UI")
}

// Convert 2-byte hex string to voltage
// 0x0020 BatteryVoltage -  The BatteryVoltage attribute is 8 bits in length and specifies the current actual (measured) battery voltage, in units of 100mV.
private parseBattery(valueHex) {
    state.batteryLevelLastReceived = new Date().format('yyyy-MM-dd HH:mm:ss')
    logDebug("Battery parse string = ${valueHex}")
	def rawVolts = Integer.parseInt(valueHex, 16)
	def batteryVolts = (rawVolts / 10).setScale(2, BigDecimal.ROUND_HALF_UP)
	def minVolts = 20
	def maxVolts = 30
	def pct = (((rawVolts - minVolts) / (maxVolts - minVolts)) * 100).toInteger()
	def batteryValue = Math.min(100, pct)
	if (batteryValue > 0){
		if (batteryavailable) sendEvent("name": "battery", "value": batteryValue, "unit": "%", isStateChange: true, descriptionText: "$device.displayName battery health is $batteryValue %", type: "Digital")
		if (batteryvoltage) sendEvent("name": "battery", "value": batteryVolts, "unit": "volts", descriptionText: "$device.displayName battery voltage is $batteryVolts", type: "Digital") 
		if (infoLogging) log.info "$device.displayName battery changed to $batteryValue%"
		if (infoLogging) log.info "$device.displayName voltage changed to $batteryVolts volts"
	}
	return result
}

// lifecycle methods -------------

// installed() runs just after a sensor is paired
def installed() {
	logInfo("Installing")    
    return refresh()
}

// configure() runs after installed() when a sensor is paired or reconnected
def configure() {
	logInfo("Configuring")
    return configureReporting()
}

def refresh() {
    logInfo("Refreshing")
    return zigbee.readAttribute(zigbee.POWER_CONFIGURATION_CLUSTER, 0x0020)
}

// updated() runs every time user saves preferences
def updated() {
    state.remove("batteryLevelLastReceived")
	logInfo("Updating preference settings")
    
    return configureReporting()
}

private def configureReporting() {
    def seconds = Math.round((batteryReportingHours ?: 12)*3600)
    
    logInfo("Battery reporting frequency: ${seconds/3600}h")    
    
    return zigbee.configureReporting(zigbee.POWER_CONFIGURATION_CLUSTER, 0x0020, DataType.UINT8, seconds, seconds, 0x01)
        + zigbee.readAttribute(zigbee.POWER_CONFIGURATION_CLUSTER, 0x20)
}

private def logDebug(message) {
	if (debugLogging) log.debug "${device.displayName}: ${message}"
}

private def logInfo(message) {
	if (infoLogging)
		log.info "${device.displayName}: ${message}"
}
