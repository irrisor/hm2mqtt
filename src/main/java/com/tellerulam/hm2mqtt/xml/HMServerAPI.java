package com.tellerulam.hm2mqtt.xml;

public interface HMServerAPI {
    String init(String callbackURL, String id);

    Object getValue(String address, String datapoint);

    Object setValue(String address, String datapoint, Object convertedValue);
}
