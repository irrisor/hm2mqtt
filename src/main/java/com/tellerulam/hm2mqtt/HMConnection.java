package com.tellerulam.hm2mqtt;

import com.tellerulam.hm2mqtt.binary.HMXRMsg;

import java.io.IOException;
import java.text.ParseException;
import java.util.List;
import java.util.Map;

public interface HMConnection {
    void sendInit();

    void handleEvent(List<?> parms);

    void getValue(DeviceInfo di, String topic, String datapoint, String value);

    void setValue(DeviceInfo di, String datapoint, String value);

    void handleNewDevices(List<?> parms) throws IOException, ParseException;

    /*
     * This is called by the XML-RPC server to query our list of known devices.
     * We only need to fill in address and version.
     */
    HMXRMsg handleListDevices(List<?> parms);

    void handleDeleteDevices(List<?> parms);

    @SuppressWarnings("unchecked")
    Map<String, DatapointInfo> getParamsetDescription(String address, String which) throws IOException, ParseException;

    void reportValueUsage(DeviceInfo di, String datapoint, boolean use);

    void sendPing();
}
