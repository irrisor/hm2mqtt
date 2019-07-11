package com.tellerulam.hm2mqtt;

import org.eclipse.paho.client.mqttv3.MqttException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.Timer;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Main
{
	static final Timer t=new Timer(true);

	private static String getVersion()
	{
		// First, try the manifest tag
		String version=Main.class.getPackage().getImplementationVersion();
		if(version==null)
		{
			// Read from build.gradle instead
			try
			{
				List<String> buildFile=Files.readAllLines(Paths.get("build.gradle"),StandardCharsets.UTF_8);
				Pattern p=Pattern.compile("version.*=.*'([^']+)");
				for(String l:buildFile)
				{
					Matcher m=p.matcher(l);
					if(m.find())
						return m.group(1);
				}
			}
			catch(IOException e)
			{
				/* Ignore, no version */
			}
		}
		return version;
	}

	public static void main(String[] args) throws MqttException, SecurityException, IOException
	{
		/*
		 * Interpret all command line arguments as property definitions (without the hm2mqtt prefix)
		 */
		for(String s:args)
		{
			String sp[]=s.split("=",2);
			if(sp.length!=2)
			{
				System.out.println("Invalid argument (no '='): "+s);
				System.exit(1);
			}
			System.setProperty("hm2mqtt."+sp[0],sp[1]);
		}
		SyslogHandler.readConfig();
		Logger.getLogger(Main.class.getName()).info("hm2mqtt V"+getVersion()+" (C) 2015-16 Oliver Wagner <owagner@tellerulam.com>");


//		try {
//			WebServer webserver = new WebServer (12345, InetAddress.getByName("192.168.178.33"));
//			webserver.start ();
//			webserver.getXmlRpcServer().setErrorLogger(new XmlRpcErrorLogger() {
//				@Override
//				public void log(String pMessage, Throwable pThrowable) {
//					Logger.getLogger(XmlRpcErrorLogger.class.getName()).log(Level.SEVERE, pMessage, pThrowable);
//				}
//
//				@Override
//				public void log(String pMessage) {
//					Logger.getLogger(XmlRpcErrorLogger.class.getName()).log(Level.SEVERE, pMessage);
//				}
//			});
//			webserver.getXmlRpcServer().setHandlerMapping(new XmlRpcHandlerMapping() {
//				@Override
//				public XmlRpcHandler getHandler(String handlerName) throws XmlRpcNoSuchHandlerException, XmlRpcException {
//					return new XmlRpcHandler() {
//						@Override
//						public Object execute(XmlRpcRequest pRequest) throws XmlRpcException {
//							ArrayList<Object> params = new ArrayList<>();
//							for (int i = 0; i < pRequest.getParameterCount(); i++) {
//								params.add(pRequest.getParameter(i));
//							}
//							System.out.println("Received XML-RCP method call " + pRequest.getMethodName() + " with " + params);
//							return Collections.emptyList();
//						}
//					};
//				}
//			});
//
//			XmlRpcClient xmlrpc = new XmlRpcClient();
//			XmlRpcClientConfigImpl config = new XmlRpcClientConfigImpl();
//			config.setServerURL(new URL("http://192.168.178.55:2010/"));
//			xmlrpc.setConfig(config);
//			Vector params = new Vector();
//			params.addElement("http://192.168.178.33:12345");
//			params.addElement("hm2mqtt_hmip");
//			// this method returns a string
//			String result = (String) xmlrpc.execute("init", params.toArray());
//			System.out.println("result: " + result);
//
//		} catch (Exception e) {
//			e.printStackTrace();
//		}

		DeviceInfo.loadDeviceInfos();
		MQTTHandler.init();
		HM.init();
	}
}
