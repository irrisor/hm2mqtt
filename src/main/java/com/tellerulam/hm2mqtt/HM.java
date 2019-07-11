package com.tellerulam.hm2mqtt;

import com.tellerulam.hm2mqtt.binary.HMXRConnection;
import com.tellerulam.hm2mqtt.binary.HMXRMsg;
import com.tellerulam.hm2mqtt.binary.XMLRPCServer;
import com.tellerulam.hm2mqtt.xml.HMXmlConnection;

import java.io.IOException;
import java.text.ParseException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimerTask;
import java.util.logging.Level;
import java.util.logging.Logger;

public class HM {
    private static final int hmIdleTimeout = Integer.getInteger("hm2mqtt.hm.idleTimeout", 120).intValue();
    private static HM instance;
    final private Map<String, HMConnection> connections = new HashMap<>();
    private final Logger L = Logger.getLogger(getClass().getName());

    static void init() {
        instance = new HM();
        try {
            instance.doInit();
        } catch (Exception e) {
            instance.L.log(Level.SEVERE, "Error setting up HM interface", e);
            System.exit(1);
        }
    }

    public static void dispatchEvent(List<?> parms) {
        String cbid = parms.get(0).toString();
        HMConnection c = instance.connections.get(cbid);
        if (c == null) {
            instance.L.warning("Received event with unknown callback ID " + parms);
            return;
        }
        c.handleEvent(parms);
    }

    public static void dispatchNewDevices(List<?> parms) throws IOException, ParseException {
        String cbid = parms.get(0).toString();
        HMConnection c = instance.connections.get(cbid);
        if (c == null) {
            instance.L.warning("Received newDevices with unknown callback ID " + parms);
            return;
        }
        c.handleNewDevices(parms);
    }

    public static void dispatchDeleteDevices(List<?> parms) {
        String cbid = parms.get(0).toString();
        HMConnection c = instance.connections.get(cbid);
        if (c == null) {
            instance.L.warning("Received deleteDevices with unknown callback ID " + parms);
            return;
        }
        c.handleDeleteDevices(parms);
    }

    public static HMXRMsg dispatchListDevices(List<?> parms) {
        String cbid = parms.get(0).toString();
        HMConnection c = instance.connections.get(cbid);
        if (c == null) {
            instance.L.warning("Received listDevices with unknown callback ID " + parms);
            return null;
        }
        return c.handleListDevices(parms);
    }

    public static void setValue(DeviceInfo di, String datapoint, String value) {
        HMConnection c = instance.connections.get(di.ifid);
        if (c == null) {
            instance.L.warning("Unable to find a HM connection for device " + di);
            return;
        }
        c.setValue(di, datapoint, value);
    }

    public static void getValue(DeviceInfo di, String topic, String datapoint, String value) {
        HMConnection c = instance.connections.get(di.ifid);
        if (c == null) {
            instance.L.warning("Unable to find a HM connection for device " + di);
            return;
        }
        c.getValue(di, topic, datapoint, value);
    }

    public static void reportValueUsage(DeviceInfo di, String datapoint, boolean active) {
        HMConnection c = instance.connections.get(di.ifid);
        if (c == null) {
            instance.L.warning("Unable to find a HM connection for device " + di);
            return;
        }
        c.reportValueUsage(di, datapoint, active);
    }

    private void sendInits() {
        for (HMConnection c : connections.values()) {
            c.sendInit();
        }
    }

    private void sendPings() {
        for (HMConnection c : connections.values()) {
            c.sendPing();
        }
    }

    private void doInit() throws IOException {
        String serverurl = XMLRPCServer.init();
        L.info("Listening for XML-RPC callbacks on " + serverurl);
        String hmhosts = System.getProperty("hm2mqtt.hm.host");
        if (hmhosts == null) {
            throw new IllegalArgumentException("You must specify hm.host with the address of the CCU or XML-RPC server");
        }
        Set<String> regas = new HashSet<>();
        for (String h : hmhosts.split(",")) {
            String hp[] = h.split(":", 2);
            if (hp.length == 2) {
                addConnection(hp[0], Integer.parseInt(hp[1]), serverurl);
            } else {
                addConnection(hp[0], 2000, serverurl);
                addConnection(hp[0], 2001, serverurl);
            }
            regas.add(hp[0]);
        }
        for (String rega : regas) {
            TCLRegaHandler.setHMHost(rega);
            ReGaDeviceNameResolver.fetchDeviceNames();
            //ReGaDeviceCache.loadDeviceCache();
        }

        sendInits();

        // Start a watchdog
        Main.t.schedule(new TimerTask() {
            @Override
            public void run() {
                long idle = XMLRPCServer.getIdleTime();

                if (idle > (hmIdleTimeout * 2000)) {
                    // TODO use new Xml server as well
                    L.info("Not seen a XML-RPC request for over " + hmIdleTimeout * 2 + "s, re-initing...");
                    sendInits();
                } else if (idle > (hmIdleTimeout * 1000)) {
                    L.info("Not seen a XML-RPC request for over " + hmIdleTimeout * 2 + "s, sending PING");
                    sendPings();
                }
            }
        }, 30 * 1000, 30 * 1000);
    }

    private void addConnection(String host, int port, String serverurl) {
        int ix = connections.size();
        String cbid = "CB" + ix;
        /*
         * TODO Workaround against a bug in current CuXD versions
         */
        if (port == 8701) {
            cbid = "CUxD";
        }
        HMConnection c;
        if (port == 2010) {
            c = new HMXmlConnection(host, port, cbid);
        } else {
            c = new HMXRConnection(host, port, serverurl, cbid);
        }
        connections.put(cbid, c);
        L.info("Adding connection " + cbid + " to XML-RPC service at " + host + ":" + port);
        ix++;
    }

}
