/*
 * Handles a outgoing "XML-RPC" connection with the binary encoded CCU format
 */

package com.tellerulam.hm2mqtt.binary;

import com.tellerulam.hm2mqtt.DatapointInfo;
import com.tellerulam.hm2mqtt.DeviceInfo;
import com.tellerulam.hm2mqtt.HMConnection;
import com.tellerulam.hm2mqtt.ReGaDeviceNameResolver;

import java.io.IOException;
import java.net.*;
import java.text.ParseException;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.*;

public class HMXRConnection extends Thread implements HMConnection {
	private final Logger L=Logger.getLogger(getClass().getName());

	Socket s;
	final String host;
	final int port;
	final String serverurl;
	final String cbid;

	private final boolean cuxDkludges;

	private boolean initFinished, newDevicesFinished=true, listDevicesFinished;

	public HMXRConnection(String host, int port, String serverurl, String cbid)
	{
		this.host=host;
		this.port=port;
		this.serverurl=serverurl;
		this.cbid=cbid;

		cuxDkludges=(port==8701);

		// Queue a deinit on shutdown
		Runtime.getRuntime().addShutdownHook(new Deiniter());
	}

	// Deinit on shutdown
	private class Deiniter extends Thread
	{
		@Override
		public void run()
		{
			try
			{
				HMXRMsg m=new HMXRMsg("init");
				m.addArg(serverurl);
				sendRequest(m,false);
			}
			catch(Exception e)
			{
				/* Ignore, we're dying anyway */
			}
		}
	}

	@Override
	public void sendInit()
	{
		if(cuxDkludges && !listDevicesFinished)
		{
			L.info("CUXD-Mode -- sending explicit listDevices first");
			HMXRMsg m=new HMXRMsg("listDevices");
			m.addArg(serverurl);
			m.addArg(cbid);
			try
			{
				HMXRResponse r=sendRequest(m,false);
				handleNewDevices(r.getData());
				listDevicesFinished=true;
			}
			catch(Exception e)
			{
				L.log(Level.WARNING,"listDevices call to CUXD failed",e);
			}
		}

		L.info("Sending init to "+host+":"+port+" with "+serverurl);
		HMXRMsg m=new HMXRMsg("init");
		m.addArg(serverurl);
		m.addArg(cbid);
		try
		{
			sendRequest(m,false);
			L.info("Init to "+host+":"+port+" with "+serverurl+" successful");
			initFinished=true;
		}
		catch(Exception e)
		{
			L.log(Level.WARNING,"Init to "+host+":"+port+" with "+serverurl+" failed",e);
		}
	}

	public synchronized HMXRResponse sendRequest(HMXRMsg m, boolean retry) throws IOException, ParseException
	{
		try
		{
			if(s==null)
				s=new Socket(host,port);

			s.getOutputStream().write(m.prepareData());
			return HMXRResponse.readMsg(s,false);
		}
		catch(Exception ioe)
		{
			// In any case, close the socket, so it's reopened upon retry
			try
			{
				s.close();
			}
			catch(Exception e)
			{
				/* Ignore anything that happened during closing, we don't care */
			}
			s=null;

			if(retry)
				throw ioe; // Just rethrow

			L.log(Level.WARNING,"Error during transaction handling",ioe);
			try
			{
				sleep(10000);
			}
			catch(InterruptedException ie)
			{
				/* Ignore */
			}
			return sendRequest(m,true);
		}
	}
	public HMXRResponse sendRequest(HMXRMsg m) throws IOException, ParseException
	{
		return sendRequest(m,true);
	}

	static public String channelIDtoAddress(String chid)
	{
		int six=chid.indexOf(':');
		if(six>=0)
			return chid.substring(0,six);
		return chid;
	}

	private final Set<String> warnedUnknownAddress=new HashSet<String>();

	@Override
	public void handleEvent(List<?> parms)
	{
		String address=parms.get(1).toString();
		String datapoint=parms.get(2).toString();
		Object val=parms.get(3);

		L.finest("Got CB "+address+" "+datapoint+" "+val);

		String topic;

		if("CENTRAL".equals(address))
		{
			// We got a ping reply
			return;
		}

		DeviceInfo di=DeviceInfo.getByAddress(address);
		if(di==null)
		{
			if(initFinished && newDevicesFinished)
				L.severe("Got event for unknown device address "+address+" - this should not happen and suggests a problem in init/newDevice/listDevice synchronisation!");
			return;
		}
		// We don't want to retain ACTION one-shot keypress notifications
		DatapointInfo dpi=di.getValueDatapointInfo(datapoint);
		boolean retain=!dpi.isAction();

		if(di.name==null)
		{
			// We only want to warn once per unknown device
			if(warnedUnknownAddress.add(di.address))
				L.warning("Unable to resolve address "+address+" to a ReGa name");
			topic=address;
			ReGaDeviceNameResolver.queueNameFetch();
			// If the name resolution failure is likely to be temporary, do not retain the value
			if(ReGaDeviceNameResolver.couldTheoreticallyResolveNames())
				retain=false;
		}
		else
			topic=di.name;
		dpi.publish(topic, val, di.address, null);
	}

	static private Executor longRunningRequestDispatcher=Executors.newCachedThreadPool();

	@Override
	public void getValue(final DeviceInfo di, final String topic, final String datapoint, final String value)
	{
		longRunningRequestDispatcher.execute(new Runnable(){
			@Override
			public void run()
			{
				try
				{
					HMXRMsg m=new HMXRMsg("getValue");
					m.addArg(di.address);
					m.addArg(datapoint);
					HMXRResponse response=sendRequest(m);
					if(response.isFailedRequest())
						L.log(Level.INFO,"getValue on "+di+"/"+datapoint+" failed with "+response.getFaultCode()+": "+response.getFaultString());
					else
					{
						// We don't want to retain ACTION one-shot keypress notifications
						DatapointInfo dpi=di.getValueDatapointInfo(datapoint);
						dpi.publish(topic, response.getData().get(0), di.address, value);
					}
				}
				catch(IOException | ParseException e)
				{
					L.log(Level.WARNING,"Error when getting value "+datapoint+" from "+di,e);
				}
			}
		});
	}

	@Override
	public void setValue(DeviceInfo di, String datapoint, String value)
	{
		HMXRMsg m=new HMXRMsg("setValue");
		m.addArg(di.address);
		m.addArg(datapoint);

		try
		{
			DatapointInfo dpi=di.getValueDatapointInfo(datapoint);
			if(dpi==null)
			{
				L.info("Unknown datapoint "+di.address+"."+datapoint+", ignoring set");
				return;
			}
			m.addArg(dpi.convertedValue(value));
			HMXRResponse response=sendRequest(m);
			L.log(Level.INFO,"setValue returned "+response);
			return;
		}
		catch(IOException | ParseException e)
		{
			L.log(Level.WARNING,"Error when setting value "+datapoint+"="+value+" on "+di,e);
		}
	}

	/*
	 * This is called by the XML-RPC server to tell us about all new devices
	 * (i.e. devices it knows, and we didn't include in our call to listDevices)
	 */

	@Override
	public void handleNewDevices(List<?> parms) throws IOException, ParseException
	{
		newDevicesFinished=false;
		@SuppressWarnings("unchecked")
		List<Map<String,Object>> items=(List<Map<String, Object>>)parms.get(1);
		L.info("XML-RPC server "+cbid+" informs us about "+items.size()+" new devices");
		ReGaDeviceNameResolver.queueNameFetch();
		for(Map<String,Object> dev:items)
		{
			DeviceInfo di=DeviceInfo.addNewDevice(
				(String)dev.get("ADDRESS"),
				((Number)dev.get("VERSION")).intValue(),
				cbid
			);
			@SuppressWarnings("unchecked")
			List<String> paramSets=(List<String>)dev.get("PARAMSETS");
			if(paramSets.contains("VALUES"))
			{
				di.values=getParamsetDescription(di.address, "VALUES");
			}
			if(paramSets.contains("MASTER"))
			{
				di.params=getParamsetDescription(di.address, "MASTER");
			}
		}
		newDevicesFinished=true;
		DeviceInfo.saveDeviceInfos();
		L.info("Finished processing new device list");
	}

	/*
	 * This is called by the XML-RPC server to query our list of known devices.
	 * We only need to fill in address and version.
	 */
	@Override
	public HMXRMsg handleListDevices(List<?> parms)
	{
		List<Object> devList=new ArrayList<>();
		for(DeviceInfo di:DeviceInfo.getDeviceInfosForInterface(cbid))
		{
			Map<String,Object> dev=new HashMap<>();
			dev.put("ADDRESS",di.address);
			dev.put("VERSION",Integer.valueOf(di.version));
			devList.add(dev);
		}
		HMXRMsg m=new HMXRMsg(null);
		L.info("Replying with "+devList.size()+" known devices to listDevices call from "+cbid);
		m.addArg(devList);
		return m;
	}

	@Override
	public void handleDeleteDevices(List<?> parms)
	{
		@SuppressWarnings("unchecked")
		List<String> items=(List<String>)parms.get(1);
		L.info("XML-RPC server "+cbid+" informs us about "+items.size()+" deleted devices");
		for(Object address:items)
		{
			DeviceInfo.removeDevice((String)address);
			L.info("Deleting device "+address);
		}
		DeviceInfo.saveDeviceInfos();
	}


	@Override
	@SuppressWarnings("unchecked")
	public Map<String, DatapointInfo> getParamsetDescription(String address, String which) throws IOException, ParseException
	{
		L.fine("Obtaining paramSetDescription for "+which+" from "+address);
		Map<String, DatapointInfo> res=new HashMap<>();
		HMXRMsg m=new HMXRMsg("getParamsetDescription");
		m.addArg(address);
		m.addArg(which);
		HMXRResponse r=sendRequest(m);
		for(Object d:r.getData())
		{
			if(d instanceof Map)
			{
				for(Map.Entry<String,Map<String,Object>> me:((Map<String,Map<String,Object>>)d).entrySet())
				{
					String datapoint=me.getKey();
					Map<String,Object> paramset=me.getValue();
					DatapointInfo di=DatapointInfo.constructFromParamsetDescription(paramset);
					res.put(datapoint,di);
				}
			}
		}
		return res;
	}

	@Override
	public void reportValueUsage(DeviceInfo di, String datapoint, boolean use)
	{
		HMXRMsg m=new HMXRMsg("reportValueUsage");
		m.addArg(di.address);
		m.addArg(datapoint);
		m.addArg(Integer.valueOf(use?1:0));
		try
		{
			sendRequest(m);
		}
		catch(IOException | ParseException e)
		{
			L.log(Level.WARNING,"Error when reportValueUsage "+use+" on "+di,e);
		}
	}


	@Override
	public void sendPing()
	{
		HMXRMsg m=new HMXRMsg("ping");
		m.addArg("hmq2mqtt");
		try
		{
			sendRequest(m);
		}
		catch(IOException | ParseException e)
		{
			L.log(Level.WARNING,"Error when sending ping",e);
		}
	}

}
