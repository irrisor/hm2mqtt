package com.tellerulam.hm2mqtt.xml;

import com.tellerulam.hm2mqtt.DatapointInfo;
import com.tellerulam.hm2mqtt.DeviceInfo;
import com.tellerulam.hm2mqtt.HMConnection;
import com.tellerulam.hm2mqtt.binary.HMXRMsg;
import org.apache.xmlrpc.client.XmlRpcClient;
import org.apache.xmlrpc.client.XmlRpcClientConfigImpl;
import org.apache.xmlrpc.client.util.ClientFactory;
import org.apache.xmlrpc.server.XmlRpcErrorLogger;
import org.apache.xmlrpc.webserver.WebServer;

import java.io.IOException;
import java.net.InetAddress;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.UnknownHostException;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

public class HMXmlConnection implements HMConnection {

    private final Logger L = Logger.getLogger(getClass().getName());
    private final String cbid;
    private WebServer webserver;
    private InetAddress localHost;
    private XmlRpcClient client;
    private HMServerAPI hmApi;

    public HMXmlConnection(String host, int port, String cbid) {
        this.cbid = cbid;
        client = new XmlRpcClient();
        XmlRpcClientConfigImpl config = new XmlRpcClientConfigImpl();
        try {
            config.setServerURL(new URL("http://" + host + ":" + port + "/"));
        } catch (MalformedURLException e) {
            throw new IllegalArgumentException("Cannot create URL from hostname and port.", e);
        }
        config.setContentLengthOptional(true);
        config.setEnabledForExceptions(true);
        callbackURL();
        config.setXmlRpcServer(webserver.getXmlRpcServer());
        client.setConfig(config);
        hmApi = (HMServerAPI) new ClientFactory(client).newInstance(
                Thread.currentThread().getContextClassLoader(),
                HMServerAPI.class,
                null);
    }

    private String callbackURL() {
        if (webserver == null) {
            String bindaddress = System.getProperty("hm2mqtt.hm.xml.bindaddress");
            try {
                String localhost = System.getProperty("hm2mqtt.hm.localhost");
                if (localhost == null) {
                    localhost = localHost.getHostAddress();
                }
                if (bindaddress == null) {
                    localHost = InetAddress.getByName(localhost);
                    webserver = new WebServer(0, localHost);
                } else {
                    String[] parts = bindaddress.split(":");
                    localHost = InetAddress.getByName(parts[0]);
                    webserver = new WebServer(parts.length > 1 ? Integer.valueOf(parts[1]) : 0, localHost);
                }

                webserver.start();
                webserver.getXmlRpcServer().setErrorLogger(new XmlRpcErrorLogger() {
                    @Override
                    public void log(String pMessage, Throwable pThrowable) {
                        Logger.getLogger(XmlRpcErrorLogger.class.getName()).log(Level.SEVERE, pMessage, pThrowable);
                    }

                    @Override
                    public void log(String pMessage) {
                        Logger.getLogger(XmlRpcErrorLogger.class.getName()).log(Level.SEVERE, pMessage);
                    }
                });
                webserver.getXmlRpcServer().setHandlerMapping(handlerName -> pRequest -> {
                    ArrayList<Object> params = new ArrayList<>();
                    for (int i = 0; i < pRequest.getParameterCount(); i++) {
                        params.add(pRequest.getParameter(i));
                    }
                    System.out.println("Received XML-RCP method call " + pRequest.getMethodName() + " with " + params);
                    return Collections.emptyList();
                });
            } catch (UnknownHostException e) {
                throw new IllegalArgumentException("Cannot open XML RCP server at bind address " + bindaddress, e);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        return "http://" + localHost.getHostAddress() + ":" + webserver.getPort();
    }

    @Override
    public void sendInit() {
        hmApi.init(callbackURL(), cbid);
    }

    @Override
    public void getValue(DeviceInfo di, String topic, String datapoint, String value) {
        Object responseValue = hmApi.getValue(di.address, datapoint);
        // We don't want to retain ACTION one-shot keypress notifications
        DatapointInfo dpi = di.getValueDatapointInfo(datapoint);
        dpi.publish(topic, responseValue, di.address, value);
    }

    @Override
    public void setValue(DeviceInfo di, String datapoint, String value) {
        DatapointInfo dpi = di.getValueDatapointInfo(datapoint);
        if (dpi == null) {
            L.info("Unknown datapoint " + di.address + "." + datapoint + ", ignoring set");
            return;
        }
        Object response = hmApi.setValue(di.address, datapoint, dpi.convertedValue(value));
        L.log(Level.INFO, "setValue returned " + response);
    }

    @Override
    public void handleEvent(List<?> parms) {
    }

    @Override
    public void handleNewDevices(List<?> parms) throws IOException, ParseException {

    }

    @Override
    public HMXRMsg handleListDevices(List<?> parms) {
        return null;
    }

    @Override
    public void handleDeleteDevices(List<?> parms) {

    }

    @Override
    public Map<String, DatapointInfo> getParamsetDescription(String address, String which) throws IOException, ParseException {
        return null;
    }

    @Override
    public void reportValueUsage(DeviceInfo di, String datapoint, boolean use) {

    }

    @Override
    public void sendPing() {

    }
}
