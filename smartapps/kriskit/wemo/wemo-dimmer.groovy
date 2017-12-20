/**
 *  Copyright 2015 Chris Kitch
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
 *  Wemo Dimmer Service Manager
 *
 *  Author: Chris Kitch
 *  Date: 2016-04-06
 */
definition(
	name: "Wemo Dimmer (Connect)",
	namespace: "kriskit.wemo",
	author: "Chris Kitch",
	description: "Allows you to integrate your WeMo Dimmer with SmartThings.",
	category: "My Apps",
	iconUrl: "https://s3.amazonaws.com/smartapp-icons/Partner/wemo.png",
	iconX2Url: "https://s3.amazonaws.com/smartapp-icons/Partner/wemo@2x.png",
	singleInstance: true
)

preferences {
	page(name:"firstPage", title:"Wemo Device Setup", content:"firstPage")
}

def firstPage()
{
    int refreshCount = !state.refreshCount ? 0 : state.refreshCount as int
        state.refreshCount = refreshCount + 1
    def refreshInterval = 5

    log.debug "REFRESH COUNT :: ${refreshCount}"

    ssdpSubscribe()

    //ssdp request every 25 seconds
    if((refreshCount % 5) == 0) {
        discoverWemoDimmers()
    }

    //setup.xml request every 5 seconds except on discoveries
    if(((refreshCount % 1) == 0) && ((refreshCount % 5) != 0)) {
        verifyDevices()
    }

    def DimmersDiscovered = DimmersDiscovered()

    return dynamicPage(name:"firstPage", title:"Discovery Started!", nextPage:"", refreshInterval: refreshInterval, install:true, uninstall: true) {
        section("Select a device...") {
            input "selectedDimmers", "enum", required:false, title:"Select Wemo Dimmers \n(${DimmersDiscovered.size() ?: 0} found)", multiple:true, options:DimmersDiscovered
        }
    }
}

def installed() {
	log.debug "Installed with settings: ${settings}"
	initialize()
}

def updated() {
	log.debug "Updated with settings: ${settings}"
	initialize()
}

def initialize() {
	unsubscribe()
	unschedule()

	ssdpSubscribe()

	if (selectedDimmers)
		addDimmers()

	runIn(5, "subscribeToDevices") //initial subscriptions delayed by 5 seconds
	runIn(10, "refreshDevices") //refresh devices, delayed by 10 seconds
	runEvery5Minutes("refresh")
}

private discoverWemoDimmers()
{
	log.debug "Sending discover request..."
	sendHubCommand(new physicalgraph.device.HubAction("lan discovery urn:Belkin:device:Dimmer:1", physicalgraph.device.Protocol.LAN))
}

private getFriendlyName(ip, deviceNetworkId) {
	log.debug "Getting friendly name from http://${ip}/setup.xml"
	sendHubCommand(new physicalgraph.device.HubAction("""GET /setup.xml HTTP/1.1\r\nHOST: $ip\r\n\r\n""", physicalgraph.device.Protocol.LAN, "${deviceNetworkId}", [callback: "setupHandler"]))
}

private verifyDevices() {
	def Dimmers = getWemoDimmers().findAll { it?.value?.verified != true }
	def devices = Dimmers
	devices.each {
    	def host = convertHexToIP(it.value.ip) + ":" + convertHexToInt(it.value.port)
        def networkId = (it.value.ip + ":" + it.value.port)
		getFriendlyName(host, networkId)
	}
}

void ssdpSubscribe() {
	if (state.ssdpSubscribed)
    	return

	log.debug "Subscribing to SSDP..."
	subscribe(location, "ssdpTerm.urn:Belkin:device:Dimmer:1", ssdpDimmerHandler)
    state.ssdpSubscribed = true
}

def devicesDiscovered() {
	def Dimmers = getWemoDimmers()
	def devices = Dimmers
	devices?.collect{ [app.id, it.ssdpUSN].join('.') }
}

def DimmersDiscovered() {
	def Dimmers = getWemoDimmers().findAll { it?.value?.verified == true }
	def map = [:]
	Dimmers.each {
		def value = it.value.name ?: "WeMo Dimmer ${it.value.ssdpUSN.split(':')[1][-3..-1]}"
		def key = it.value.mac
		map["${key}"] = value
	}
	map
}

def getWemoDimmers()
{
	if (!state.Dimmers) { state.Dimmers = [:] }
	state.Dimmers
}


def resubscribe() {
	log.debug "Resubscribe called, delegating to refresh()"
	refresh()
}

def refresh() {
	log.debug "refresh() called"
	doDeviceSync()
	refreshDevices()
}

def refreshDevices() {
	log.debug "refreshDevices() called"
	def devices = getAllChildDevices()
	devices.each { d ->
		log.debug "Calling refresh() on device: ${d.id}"
		d.refresh()
	}
}

def subscribeToDevices() {
	log.debug "subscribeToDevices() called"
	def devices = getAllChildDevices()
	devices.each { d ->
		d.subscribe()
	}
}

def addDimmers() {
	def Dimmers = getWemoDimmers()

	selectedDimmers.each { dni ->
		def selectedDimmer = Dimmers.find { it.value.mac == dni } ?: Dimmers.find { "${it.value.ip}:${it.value.port}" == dni }
		def d
		if (selectedDimmer) {
			d = getChildDevices()?.find {
				it.deviceNetworkId == selectedDimmer.value.mac || it.device.getDataValue("mac") == selectedDimmer.value.mac
			}
		}

		if (!d) {
			log.debug "Creating WeMo Dimmer with dni: ${selectedDimmer.value.mac}"
			d = addChildDevice("kriskit.wemo", "Wemo Dimmer", selectedDimmer.value.mac, selectedDimmer?.value.hub, [
				"label": selectedDimmer?.value?.name ?: "Wemo Dimmer",
				"data": [
					"mac": selectedDimmer.value.mac,
					"ip": selectedDimmer.value.ip,
					"port": selectedDimmer.value.port
				]
			])
			def ipvalue = convertHexToIP(selectedDimmer.value.ip)
			d.sendEvent(name: "currentIP", value: ipvalue, descriptionText: "IP is ${ipvalue}")
			log.debug "Created ${d.displayName} with id: ${d.id}, dni: ${d.deviceNetworkId}"
		} else {
			log.debug "found ${d.displayName} with id $dni already exists"
		}
	}
}

def ssdpDimmerHandler(evt) {
	def description = evt.description
	def hub = evt?.hubId
	def parsedEvent = parseDiscoveryMessage(description)
	parsedEvent << ["hub":hub]

	def Dimmers = getWemoDimmers()
	if (!(Dimmers."${parsedEvent.ssdpUSN.toString()}")) {
		//if it doesn't already exist
		Dimmers << ["${parsedEvent.ssdpUSN.toString()}":parsedEvent]
	} else {
		log.debug "Device was already found in state..."
		def d = Dimmers."${parsedEvent.ssdpUSN.toString()}"
		boolean deviceChangedValues = false
		log.debug "$d.ip <==> $parsedEvent.ip"
		if(d.ip != parsedEvent.ip || d.port != parsedEvent.port) {
			d.ip = parsedEvent.ip
			d.port = parsedEvent.port
			deviceChangedValues = true
			log.debug "Device's port or ip changed..."
			def child = getChildDevice(parsedEvent.mac)
			if (child) {
				child.subscribe(parsedEvent.ip, parsedEvent.port)
				child.poll()
			} else {
				log.debug "Device with mac $parsedEvent.mac not found"
			}
		}
	}
}

void setupHandler(hubResponse) {
	String contentType = hubResponse?.headers['Content-Type']
    log.debug "Response received from ${convertHexToIP(hubResponse.ip)}:${convertHexToInt(hubResponse.port)}"
	if (contentType != null && contentType == 'text/xml') {
		def body = hubResponse.xml
		def wemoDevices = []
		String deviceType = body?.device?.deviceType?.text() ?: ""
		if (deviceType.startsWith("urn:Belkin:device:Dimmer:1")) {
			wemoDevices = getWemoDimmers()
		}

		def wemoDevice = wemoDevices.find {it?.key?.contains(body?.device?.UDN?.text())}
		if (wemoDevice) {
        	def friendlyName = body?.device?.friendlyName?.text()
            log.debug "Verified '${friendlyName}'"
			wemoDevice.value << [name: friendlyName, verified: true]
		} else {
			log.error "/setup.xml returned a wemo device that didn't exist"
		}
	}
}

private def parseDiscoveryMessage(String description) {
	def event = [:]
	def parts = description.split(',')
	parts.each { part ->
		part = part.trim()
		if (part.startsWith('devicetype:')) {
			part -= "devicetype:"
			event.devicetype = part.trim()
		}
		else if (part.startsWith('mac:')) {
			part -= "mac:"
			event.mac = part.trim()
		}
		else if (part.startsWith('networkAddress:')) {
			part -= "networkAddress:"
			event.ip = part.trim()
		}
		else if (part.startsWith('deviceAddress:')) {
			part -= "deviceAddress:"
			event.port = part.trim()
		}
		else if (part.startsWith('ssdpPath:')) {
			part -= "ssdpPath:"
			event.ssdpPath = part.trim()
		}
		else if (part.startsWith('ssdpUSN:')) {
			part -= "ssdpUSN:"
			event.ssdpUSN = part.trim()
		}
		else if (part.startsWith('ssdpTerm:')) {
			part -= "ssdpTerm:"
			event.ssdpTerm = part.trim()
		}
		else if (part.startsWith('headers')) {
			part -= "headers:"
			event.headers = part.trim()
		}
		else if (part.startsWith('body')) {
			part -= "body:"
			event.body = part.trim()
		}
	}
	event
}

def doDeviceSync(){
	log.debug "Doing Device Sync!"
	discoverWemoDimmers()
}

private String convertHexToIP(hex) {
	[convertHexToInt(hex[0..1]),convertHexToInt(hex[2..3]),convertHexToInt(hex[4..5]),convertHexToInt(hex[6..7])].join(".")
}

private Integer convertHexToInt(hex) {
	Integer.parseInt(hex,16)
}

private Boolean canInstallLabs() {
	return hasAllHubsOver("000.011.00603")
}

private Boolean hasAllHubsOver(String desiredFirmware) {
	return realHubFirmwareVersions.every { fw -> fw >= desiredFirmware }
}

private List getRealHubFirmwareVersions() {
	return location.hubs*.firmwareVersionString.findAll { it }
}
