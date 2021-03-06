package io.amiko.app.devices.bledriver.bluegiga;

import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.util.Properties;

import org.apache.log4j.Logger;
import org.thingml.bglib.BDAddr;
import org.thingml.bglib.BGAPI;
import org.thingml.bglib.BGAPIListener;
import org.thingml.bglib.BGAPIPacketLogger;
import org.thingml.bglib.BGAPITransport;
import org.thingml.bglib.gui.BLED112;

import gnu.io.SerialPort;
import io.amiko.app.devices.AmikoCmd;
import io.amiko.app.devices.AmikoCmdResp;
import io.amiko.app.devices.Utils;
import io.amiko.app.devices.bledriver.BLEDevice;
import io.amiko.app.devices.bledriver.BLEDeviceList;
import io.amiko.app.devices.bledriver.BLEDriver;
import io.amiko.app.devices.bledriver.BLEDriverConnectionException;
import io.amiko.app.devices.bledriver.BLEDriverListener;

//TODO non deve contenere riferimenti ai packages di GUI
public class BluegigaBLEDriver implements BLEDriver, BGAPIListener {

	// MODEL properties
	// TODO non uso la classe BLEDeviceList perche legata alla GUI: protected
	// BLEDeviceList deviceList = new BLEDeviceList();
	private BLEDeviceList deviceList = new BLEDeviceList();
	private BLEDeviceList whiteList = new BLEDeviceList();

	// BUSINESS LOGIN properties
	private CommandScheduler scheduler;
	private Thread schedulerThread;

	// BLE CONNECTION properties
	protected int connection = -1;
	protected BLEDevice bledevice = null;
	private SerialPort port;
	private BGAPI bgapi;
	// TODO manage a single listener
	private BLEDriverListener listener;
	private Properties configFile;

	public BluegigaBLEDriver() {

		InputStream is = getClass().getResourceAsStream("bluegiga.properties");
		configFile = new Properties();
		if (is != null) {
			try {
				configFile.load(is);
			} catch (IOException e) {
				logger.error(e.getMessage(), e);
			}
		} else {
			logger.error("Property file bluegiga.properties not found");
		}
	}

	private BGAPIPacketLogger bglogger = new BGAPIPacketLogger();
	private static Logger logger = Logger.getLogger(BluegigaBLEDriver.class);

	// TODO notificare avvenuta connessione
	// TODO permettere da UI la abilitazione dei log di basso livello
	public void connect(BLEDriverListener listener, boolean debug) throws BLEDriverConnectionException {
		this.listener = listener;

		// get the vid value
		String vidStr = configFile.getProperty("dongle.vid");
		int vid = 0x2458;
		if (vidStr != null) {
			try {
				vid = Integer.parseInt(vidStr);
			} catch (NumberFormatException e) {
				logger.error("dongle.vid parameter value is not valid: " + e.getMessage(), e);
			}
		}

		// get the pid value
		String pidStr = configFile.getProperty("dongle.pid");
		int pid = 1;
		if (pidStr != null) {
			try {
				pid = Integer.parseInt(pidStr);
			} catch (NumberFormatException e) {
				logger.error("dongle.pid parameter value is not valid: " + e.getMessage(), e);
			}
		}

		String portName = null;
		try {
			portName = BLED112.autoselectSerialPort(vid, pid);
		} catch (SecurityException e) {
			logger.error(e.getMessage(), e);
		} catch (IOException e) {
			logger.error(e.getMessage(), e);
		}

		if (portName == null) {
			// nessuno stack BLE presente
			// notify: connection not established
			logger.error("[CONNECTION]-BLED112 device is not available");
			this.listener.onNotConnected("BLE stack not present or already used");
		} else {
			logger.debug("[CONNECTION]-BLED112 device is available");
			port = BLED112.connectSerial(portName);
			if (port != null) {
				logger.debug("[CONNECTION]-BLED112 device is connected");
				try {
					bgapi = new BGAPI(new BGAPITransport(port.getInputStream(), port.getOutputStream()));
					bgapi.addListener(this);

					Thread.sleep(250);

					bgapi.send_system_get_info();
					if (debug) {
						// enable BGAPI logging
						bgapi.getLowLevelDriver().addListener(bglogger);
					}

					this.scheduler = new CommandScheduler(this, this.listener);
					this.schedulerThread = new Thread(this.scheduler);
					this.schedulerThread.start();
					// notify: connection is established
					this.listener.onConnected();
				} catch (Exception ex) {
					logger.error(ex.getMessage(), ex);
					throw new BLEDriverConnectionException("Exception while connecting to " + port);
				}
			} else {
				logger.error("[CONNECTION]-BLED112 device is not connected");
				// notify: connection not established
				this.listener.onNotConnected("Connection cannot be established");
			}
		}
	}

	public void disconnect() {
		if (bgapi != null) {
			bgapi.removeListener(this);
			bgapi.getLowLevelDriver().removeListener(bglogger);
			bgapi.send_system_reset(0);
			bgapi.disconnect();
			this.scheduler.stop();
			this.schedulerThread = null;
		}
		if (port != null) {
			port.close();
		}
		bgapi = null;
		port = null;
		this.listener.onNotConnected("BLE stack disconnected");
	}

	/**
	 * Sets the discovery parameters
	 */
	public void startBLEDevicesDiscovering() {
		// reset the devices list
		deviceList.clear();
		logger.debug("[BLE-DISCOVERING]-device list has been cleaned");
		// AMIKO: documentation page 108
		// TODO MIN esternalizzare i parametri sotto riportati
		logger.debug("[BLE-DISCOVERING]-set the discovering parameters (send_gap_set_scan_parameters-p.108)");
		bgapi.send_gap_set_scan_parameters(50, 50, 1);
		// AMIKO: documentation page 96
		// TODO MIN esternalizzare i parametri sotto riportati
		logger.debug("[BLE-DISCOVERING]-enable the ACTIVE discovery mode(send_gap_discover-p.96)");
		// TODO adv 0 - Discover limited
		// TODO adv 2 - Discover All devices
		bgapi.send_gap_discover(2);
	}

	public void stopBLEDevicesDiscovering() {
		bgapi.send_gap_end_procedure();
	}

	public void connectBLEDevice(BLEDevice device) {
		logger.debug(
				"[BLEDEVICE-CONNECTION]-send_gap_connect_direct address=" + BDAddr.fromString(device.getAddress()));
		// AMIKO: documentation page 92
		bgapi.send_gap_connect_direct(BDAddr.fromString(device.getAddress()), 1, 0x3C, 0x3C, 0x64, 0);
	}

	public void disconnectBLEDevice(BLEDevice device) {
		bledevice = null;
		if (connection >= 0) {
			bgapi.send_connection_disconnect(connection);
		}
		// TODO old code before connection issue
		// this.listener.onBLEDeviceDisconnected("Client has requested a
		// disconnection");	
		
	}

	public void sendCmd(AmikoCmd command) {
		logger.debug("[SEND-REQUEST]-push the new command into the queue " + command.getType());
		this.scheduler.pushCommand(command);
	}

	public void writeCmd(AmikoCmd command) {
		bgapi.send_attclient_attribute_write(connection, 0xE, command.getBytes());
		logger.debug("[SEND-REQUEST]-Command has been sent");
		logger.debug("[SEND-REQUEST]-COMPLETED");
	}

	public void clean() {
		this.scheduler.cleanCommandsQueue();
	}

	public void cleanDevicesLists(){
		this.deviceList.clear();
		this.whiteList.clear();
		logger.debug("[BLE-DISCOVERING]-Devices list has been updated");
		this.listener.onDiscoveredBLEDevices(deviceList);
	}
	
	@Override
	public void receive_system_reset() {
		// TODO Auto-generated method stub

	}

	@Override
	public void receive_system_hello() {
		// TODO Auto-generated method stub

	}

	@Override
	public void receive_system_address_get(BDAddr address) {
		// TODO Auto-generated method stub

	}

	@Override
	public void receive_system_reg_write(int result) {
		// TODO Auto-generated method stub

	}

	@Override
	public void receive_system_reg_read(int address, int value) {
		// TODO Auto-generated method stub

	}

	@Override
	public void receive_system_get_counters(int txok, int txretry, int rxok, int rxfail) {
		// TODO Auto-generated method stub

	}

	@Override
	public void receive_system_get_connections(int maxconn) {
		// TODO Auto-generated method stub

	}

	@Override
	public void receive_system_read_memory(int address, byte[] data) {
		// TODO Auto-generated method stub

	}

	@Override
	public void receive_system_get_info(int major, int minor, int patch, int build, int ll_version,
			int protocol_version, int hw) {
		// TODO Auto-generated method stub

	}

	@Override
	public void receive_system_endpoint_tx() {
		// TODO Auto-generated method stub

	}

	@Override
	public void receive_system_whitelist_append(int result) {
		// TODO Auto-generated method stub

	}

	@Override
	public void receive_system_whitelist_remove(int result) {
		// TODO Auto-generated method stub

	}

	@Override
	public void receive_system_whitelist_clear() {
		// TODO Auto-generated method stub

	}

	@Override
	public void receive_system_boot(int major, int minor, int patch, int build, int ll_version, int protocol_version,
			int hw) {
		// TODO Auto-generated method stub

	}

	@Override
	public void receive_system_debug(byte[] data) {
		// TODO Auto-generated method stub

	}

	@Override
	public void receive_system_endpoint_rx(int endpoint, byte[] data) {
		// TODO Auto-generated method stub

	}

	@Override
	public void receive_flash_ps_defrag() {
		// TODO Auto-generated method stub

	}

	@Override
	public void receive_flash_ps_dump() {
		// TODO Auto-generated method stub

	}

	@Override
	public void receive_flash_ps_erase_all() {
		// TODO Auto-generated method stub

	}

	@Override
	public void receive_flash_ps_save(int result) {
		// TODO Auto-generated method stub

	}

	@Override
	public void receive_flash_ps_load(int result, byte[] value) {
		// TODO Auto-generated method stub

	}

	@Override
	public void receive_flash_ps_erase() {
		// TODO Auto-generated method stub

	}

	@Override
	public void receive_flash_erase_page(int result) {
		// TODO Auto-generated method stub

	}

	@Override
	public void receive_flash_write_words() {
		// TODO Auto-generated method stub

	}

	@Override
	public void receive_flash_ps_key(int key, byte[] value) {
		// TODO Auto-generated method stub

	}

	@Override
	public void receive_attributes_write(int result) {
		// TODO Auto-generated method stub

	}

	@Override
	public void receive_attributes_read(int handle, int offset, int result, byte[] value) {
		// TODO Auto-generated method stub

	}

	@Override
	public void receive_attributes_read_type(int handle, int result, byte[] value) {
		// TODO Auto-generated method stub

	}

	@Override
	public void receive_attributes_user_response() {
		// TODO Auto-generated method stub

	}

	@Override
	public void receive_attributes_value(int connection, int reason, int handle, int offset, byte[] value) {
		System.out
				.println("Attribute Value att=" + Integer.toHexString(handle) + " val = " + Utils.bytesToString(value));
	}

	@Override
	public void receive_attributes_user_request(int connection, int handle, int offset) {
		// TODO Auto-generated method stub

	}

	@Override
	public void receive_connection_disconnect(int connection, int result) {
		// TODO Auto-generated method stub

	}

	@Override
	public void receive_connection_get_rssi(int connection, int rssi) {
		// TODO Auto-generated method stub

	}

	@Override
	public void receive_connection_update(int connection, int result) {
		// TODO Auto-generated method stub

	}

	@Override
	public void receive_connection_version_update(int connection, int result) {
		// TODO Auto-generated method stub

	}

	@Override
	public void receive_connection_channel_map_get(int connection, byte[] map) {
		// TODO Auto-generated method stub

	}

	@Override
	public void receive_connection_channel_map_set(int connection, int result) {
		// TODO Auto-generated method stub

	}

	@Override
	public void receive_connection_features_get(int connection, int result) {
		// TODO Auto-generated method stub

	}

	@Override
	public void receive_connection_get_status(int connection) {
		// TODO Auto-generated method stub

	}

	@Override
	public void receive_connection_raw_tx(int connection) {
		// TODO Auto-generated method stub

	}

	/**
	 * AMIKO: documentation page 90
	 */
	@Override
	public void receive_connection_status(int conn, int flags, BDAddr address, int address_type, int conn_interval,
			int timeout, int latency, int bonding) {
		logger.debug("[BLEDEVICE-CONNECTION]-receive_connection_status  connection=" + conn + " flags=" + flags
				+ " address=" + address.toString() + " timeout=" + timeout + " bounding=" + bonding);
		// flags: 0101 - 0x5 - connection_connected && connection_completed
		if ((flags & 0x5) != 0) {
			logger.debug("[BLEDEVICE-CONNECTION]-Connection has been established!");
			bledevice = this.deviceList.getFromAddress(address.toString());
			this.connection = conn;
			try {
				Thread.sleep(200);// TODO old value 2000
			} catch (InterruptedException e) {
				logger.error(e.getMessage(), e);
			}
			// inviare la scrittura DI 0X0100 sul BLE per abilitare le notifiche
			// la response � gestita in receive_attclient_write_command
			logger.debug("[BLEDEVICE-CONNECTION]-Start the Client Characteristic Configuration with value [0x01,0x00]");
			bgapi.send_attclient_write_command(connection, 0xC, new byte[] { 0x01, 0x00 });
		} else {
			logger.error("[BLEDEVICE-CONNECTION]-Flags value not expected!");
		}
	}

	@Override
	public void receive_connection_version_ind(int connection, int vers_nr, int comp_id, int sub_vers_nr) {
		// TODO Auto-generated method stub

	}

	@Override
	public void receive_connection_feature_ind(int connection, byte[] features) {
		// TODO Auto-generated method stub

	}

	@Override
	public void receive_connection_raw_rx(int connection, byte[] data) {
		// TODO Auto-generated method stub

	}

	@Override
	public void receive_connection_disconnected(int connection, int reason) {
		logger.debug("[BLEDEVICE-DISCONNECTED]-receive_connection_disconnected connection=[code:" + connection
				+ " reason=" + Utils.bytesToString(new byte[] { (byte) (reason & 0xff) }) + "]");
		bledevice = null;
		if (reason == 0x0208) {
			this.listener.onBLEDeviceDisconnected("Connection Timeout - Link supervision timeout has expired");
		} else {
			this.listener.onBLEDeviceDisconnected(
					" [code:" + Utils.bytesToString(new byte[] { (byte) (reason & 0xff) }) + "]");
		}
	}

	@Override
	public void receive_attclient_find_by_type_value(int connection, int result) {
		// TODO Auto-generated method stub

	}

	@Override
	public void receive_attclient_read_by_group_type(int connection, int result) {
		// TODO Auto-generated method stub

	}

	@Override
	public void receive_attclient_read_by_type(int connection, int result) {
		// TODO Auto-generated method stub

	}

	@Override
	public void receive_attclient_find_information(int connection, int result) {
		// TODO Auto-generated method stub
	}

	@Override
	public void receive_attclient_read_by_handle(int connection, int result) {
		// TODO Auto-generated method stub
	}

	@Override
	public void receive_attclient_attribute_write(int connection, int result) {
		bgapi.send_attclient_read_by_handle(connection, Integer.parseInt("B", 16));
	}

	@Override
	public void receive_attclient_write_command(int connection, int result) {
		if (result == 0) {
			// il comando di configurazione della caratteristica "Client
			// Characteristic Configuration" � stato accettato
			logger.debug(
					"[BLEDEVICE-CONNECTION]-Client Characteristic Configuration has been correctly updated with [0x01,0x00]");
			// notify: BLE device connected and configurated
			this.listener.onBLEDeviceConnected(bledevice);
		} else {
			// TODO gestire lo scenario in cui il comando di configurazione
			// della caratteristica "Client Characteristic Configuration" non si
			// realizzi
			logger.error("Client Characteristic Configuration has not been correctly updated");
		}

	}

	@Override
	public void receive_attclient_reserved() {
		// TODO Auto-generated method stub
	}

	@Override
	public void receive_attclient_read_long(int connection, int result) {
		// TODO Auto-generated method stub
	}

	@Override
	public void receive_attclient_prepare_write(int connection, int result) {
		// TODO Auto-generated method stub
	}

	@Override
	public void receive_attclient_execute_write(int connection, int result) {
		// TODO Auto-generated method stub
	}

	@Override
	public void receive_attclient_read_multiple(int connection, int result) {
		// TODO Auto-generated method stub
	}

	@Override
	public void receive_attclient_indicated(int connection, int attrhandle) {
		// TODO Auto-generated method stub
	}

	@Override
	public void receive_attclient_procedure_completed(int connection, int result, int chrhandle) {
		// TODO Auto-generated method stub
	}

	@Override
	public void receive_attclient_group_found(int connection, int start, int end, byte[] uuid) {
		// TODO Auto-generated method stub
	}

	@Override
	public void receive_attclient_attribute_found(int connection, int chrdecl, int value, int properties, byte[] uuid) {
		// TODO Auto-generated method stub
	}

	@Override
	public void receive_attclient_find_information_found(int connection, int chrhandle, byte[] uuid) {
		// TODO Auto-generated method stub
	}

	/**
	 * Manage the generated response from the Amiko Device
	 */
	@Override
	public void receive_attclient_attribute_value(int connection, int atthandle, int type, byte[] value) {

		logger.debug("[RESPONSE]-receive_attclient_attribute_value RAW value=" + Utils.bytesToString(value));

		// check if I have received data from the correct characteristic 0x0B
		if (atthandle == 0x0B) {
			AmikoCmdResp response = AmikoCmdResp.parse(value);
			this.scheduler.notifyResponse();
			this.listener.onResponse(response);
		} else {
			// filtering all other attribute value
		}
	}

	@Override
	public void receive_attclient_read_multiple_response(int connection, byte[] handles) {
		// TODO Auto-generated method stub

	}

	@Override
	public void receive_sm_encrypt_start(int handle, int result) {
		// TODO Auto-generated method stub
	}

	@Override
	public void receive_sm_set_bondable_mode() {
		// TODO Auto-generated method stub
	}

	@Override
	public void receive_sm_delete_bonding(int result) {
		// TODO Auto-generated method stub
	}

	@Override
	public void receive_sm_set_parameters() {
		// TODO Auto-generated method stub
	}

	@Override
	public void receive_sm_passkey_entry(int result) {
		// TODO Auto-generated method stub
	}

	@Override
	public void receive_sm_get_bonds(int bonds) {
		// TODO Auto-generated method stub

	}

	@Override
	public void receive_sm_set_oob_data() {
		// TODO Auto-generated method stub
	}

	@Override
	public void receive_sm_smp_data(int handle, int packet, byte[] data) {
		// TODO Auto-generated method stub
	}

	@Override
	public void receive_sm_bonding_fail(int handle, int result) {
		// TODO Auto-generated method stub

	}

	@Override
	public void receive_sm_passkey_display(int handle, int passkey) {
		// TODO Auto-generated method stub
	}

	@Override
	public void receive_sm_passkey_request(int handle) {
		// TODO Auto-generated method stub
	}

	@Override
	public void receive_sm_bond_status(int bond, int keysize, int mitm, int keys) {
		// TODO Auto-generated method stub
	}

	@Override
	public void receive_gap_set_privacy_flags() {
		// TODO Auto-generated method stub
	}

	@Override
	public void receive_gap_set_mode(int result) {
		// TODO Auto-generated method stub
	}

	@Override
	public void receive_gap_discover(int result) {
		// TODO Auto-generated method stub
	}

	@Override
	public void receive_gap_connect_direct(int result, int connection_handle) {
		// TODO Auto-generated method stub
	}

	@Override
	public void receive_gap_end_procedure(int result) {
		// TODO Auto-generated method stub
	}

	@Override
	public void receive_gap_connect_selective(int result, int connection_handle) {
		// TODO Auto-generated method stub
	}

	@Override
	public void receive_gap_set_filtering(int result) {
		// TODO Auto-generated method stub
	}

	@Override
	public void receive_gap_set_scan_parameters(int result) {
		// TODO Auto-generated method stub
	}

	@Override
	public void receive_gap_set_adv_parameters(int result) {
		// TODO Auto-generated method stub
	}

	@Override
	public void receive_gap_set_adv_data(int result) {
		// TODO Auto-generated method stub
	}

	@Override
	public void receive_gap_set_directed_connectable_mode(int result) {
		// TODO Auto-generated method stub
	}

	/**
	 * Callback after BLE devices discovering
	 * 
	 * @param rssi
	 * @param packet_type
	 * @param sender
	 * @param address_type
	 * @param bond
	 * @param data
	 */
	@Override
	public void receive_gap_scan_response(int rssi, int packet_type, BDAddr sender, int address_type, int bond,
			byte[] data) {
		boolean deviceListUpdate = false;

		long thershold = 5000;// 5 sec
		deviceListUpdate = deviceList.removeExpiredDevices(thershold);
		BLEDevice d = this.deviceList.getFromAddress(sender.toString());
		BLEDevice wd = this.whiteList.getFromAddress(sender.toString());
		// packet_type == 0 - Connectable Advertisement packet
		// packet_type == 4 - Scan response packet
		if (d == null && (packet_type == 0 || packet_type == 4)) {			
			// device already exists
			d = new BLEDevice(sender.toString());
			// extract the device info from the packed
			Utils.parseDeviceData(d, data);
			// get the device name
			String devName = null;
			try {
				// get the Complete Local Name
				byte[] completeLocalName = d.getDeviceInfo(0x09);
				if (completeLocalName != null) {
					devName = new String(d.getDeviceInfo(0x09), "US-ASCII");
					d.setName(devName);
				} else {
					d.setName("[name not available]");
				}
			} catch (UnsupportedEncodingException e) {
				logger.error("[BLE-DISCOVERING]-" + e.getMessage(), e);
			}
			// TODO adv
			if (d.getName().startsWith("Amiko")) {// Nordic
				this.deviceList.add(d);
				this.whiteList.add(d);
				deviceListUpdate = true;
				
			}
			logger.debug("[BLE-DISCOVERING]-Discovered a new device: " + d.toString() + " data="
					+ Utils.bytesToString(data) + "");
		} else if (d != null && (packet_type == 0 || packet_type == 4)) {			
			// il device esiste gi� in lista ma potrebbe aver cambiato nome
			d.updateTimestamp();
			// extract the device info
			Utils.parseDeviceData(d, data);
			String devName = null;
			try {
				// get the Complete Local Name
				byte[] completeLocalName = d.getDeviceInfo(0x09);
				if (completeLocalName != null) {
					devName = new String(d.getDeviceInfo(0x09), "US-ASCII");
					if (devName.startsWith("Amiko")) {
						d.setName(devName);
					}
				} else {
					d.setName("[name not available]");
				}
			} catch (UnsupportedEncodingException e) {
				logger.error("[BLE-DISCOVERING]-" + e.getMessage(), e);
			}
		} else if (wd != null && packet_type == 1 && d==null) {			
			// new advertising policy based on Direct connect (fast + slow)
			wd.enableExpiry(false);
			wd.updateTimestamp();			
			this.deviceList.add(wd);
			deviceListUpdate = true;
			logger.debug("[BLE-DISCOVERING]-Discovered a new device: " + wd.toString() + " data="
					+ Utils.bytesToString(data) + "");
		} 
		
		if (d != null) {
			d.setRssi(rssi);
		}

		if (deviceListUpdate) {
			// notify: ble device list is changed
			logger.debug("[BLE-DISCOVERING]-Devices list has been updated");
			this.listener.onDiscoveredBLEDevices(deviceList);
		}
	}

	@Override
	public void receive_gap_mode_changed(int discover, int connect) {
		// TODO Auto-generated method stub

	}

	@Override
	public void receive_hardware_io_port_config_irq(int result) {
		// TODO Auto-generated method stub

	}

	@Override
	public void receive_hardware_set_soft_timer(int result) {
		// TODO Auto-generated method stub

	}

	@Override
	public void receive_hardware_adc_read(int result) {
		// TODO Auto-generated method stub

	}

	@Override
	public void receive_hardware_io_port_config_direction(int result) {
		// TODO Auto-generated method stub

	}

	@Override
	public void receive_hardware_io_port_config_function(int result) {
		// TODO Auto-generated method stub

	}

	@Override
	public void receive_hardware_io_port_config_pull(int result) {
		// TODO Auto-generated method stub

	}

	@Override
	public void receive_hardware_io_port_write(int result) {
		// TODO Auto-generated method stub

	}

	@Override
	public void receive_hardware_io_port_read(int result, int port, int data) {
		// TODO Auto-generated method stub

	}

	@Override
	public void receive_hardware_spi_config(int result) {
		// TODO Auto-generated method stub

	}

	@Override
	public void receive_hardware_spi_transfer(int result, int channel, byte[] data) {
		// TODO Auto-generated method stub

	}

	@Override
	public void receive_hardware_i2c_read(int result, byte[] data) {
		// TODO Auto-generated method stub

	}

	@Override
	public void receive_hardware_i2c_write(int written) {
		// TODO Auto-generated method stub

	}

	@Override
	public void receive_hardware_set_txpower() {
		// TODO Auto-generated method stub

	}

	@Override
	public void receive_hardware_io_port_status(int timestamp, int port, int irq, int state) {
		// TODO Auto-generated method stub

	}

	@Override
	public void receive_hardware_soft_timer(int handle) {
		// TODO Auto-generated method stub

	}

	@Override
	public void receive_hardware_adc_result(int input, int value) {
		// TODO Auto-generated method stub

	}

	@Override
	public void receive_test_phy_tx() {
		// TODO Auto-generated method stub

	}

	@Override
	public void receive_test_phy_rx() {
		// TODO Auto-generated method stub

	}

	@Override
	public void receive_test_phy_end(int counter) {
		// TODO Auto-generated method stub

	}

	@Override
	public void receive_test_phy_reset() {
		// TODO Auto-generated method stub

	}

	@Override
	public void receive_test_get_channel_map(byte[] channel_map) {
		// TODO Auto-generated method stub

	}

	@Override
	public void dongleError() {
		this.listener.onDongleError("Bluegiga BLED112 Dongle unexpected error");
	}

}
