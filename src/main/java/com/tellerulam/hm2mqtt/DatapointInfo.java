package com.tellerulam.hm2mqtt;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

public class DatapointInfo implements Serializable {
    private static final long serialVersionUID = 3L;

    public final String name;
    public final HMValueTypes type;
    public final String unit;
    public final String enumValues[];

    public transient Object lastValue;
    public transient long lastValueTime;

    private DatapointInfo(String name, HMValueTypes type, String unit, String[] enumValues) {
        this.name = name;
        this.type = type;
        this.unit = unit;
        this.enumValues = enumValues;
    }

    public static DatapointInfo constructFromParamsetDescription(Map<String, Object> paramset) {
        @SuppressWarnings("unchecked")
        Collection<String> enumValues = (Collection<String>) paramset.get("VALUE_LIST");

        return new DatapointInfo(
                (String) paramset.get("ID"),
                HMValueTypes.valueOf((String) paramset.get("TYPE")),
                (String) paramset.get("UNIT"),
                enumValues != null ? enumValues.toArray(new String[0]) : null
        );
    }

    public boolean isAction() {
        return type == HMValueTypes.ACTION;
    }

    @Override
    public String toString() {
        return "{" + name + "}";
    }

    public void publish(String topic, Object val, String address, String getID) {
        boolean retain = !isAction();
        long ts = System.currentTimeMillis();
        List<String> moreFields = new ArrayList<>();
        if (!val.equals(lastValue)) {
            lastValue = val;
            lastValueTime = ts;
        }
        moreFields.add("ts");
        moreFields.add(String.valueOf(ts));
        moreFields.add("lc");
        moreFields.add(String.valueOf(lastValueTime));
        moreFields.add("hm_addr");
        moreFields.add(address);
        if (getID != null) {
            moreFields.add("hm_getid");
            moreFields.add(getID);
        }
        if (unit != null && unit.length() != 0) {
            moreFields.add("hm_unit");
            moreFields.add(unit);
        }
        if (type == HMValueTypes.ENUM) {
            moreFields.add("hm_enum");
            moreFields.add(enumValues[(Integer) val]);
        }
        MQTTHandler.publish(topic + "/" + this.name, val, retain, moreFields.toArray(new String[0]));
    }

    public Object convertedValue(String value) {
        switch (type) {
            case FLOAT:
                return Double.valueOf(value);
            case ENUM:
            case INTEGER:
                return (Integer.valueOf(value));
            case BOOL:
            case ACTION:
                if ("true".equalsIgnoreCase(value)) {
                    return Boolean.TRUE;
                } else if ("false".equalsIgnoreCase(value)) {
                    return Boolean.FALSE;
                } else if (Double.parseDouble(value) != 0) {
                    return Boolean.TRUE;
                } else {
                    return Boolean.FALSE;
                }
            case STRING:
            default:
                return value;
        }
    }
}
