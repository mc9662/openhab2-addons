/**
 * Copyright (c) 2010-2014, openHAB.org and others.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.openhab.binding.maxcul.internal;

import java.util.Dictionary;
import java.util.HashMap;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;

import org.openhab.binding.maxcul.MaxCulBindingProvider;

import org.apache.commons.lang.StringUtils;
import org.openhab.core.binding.AbstractActiveBinding;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.types.Command;
import org.openhab.io.transport.cul.CULDeviceException;
import org.openhab.io.transport.cul.CULHandler;
import org.openhab.io.transport.cul.CULListener;
import org.openhab.io.transport.cul.CULManager;
import org.openhab.io.transport.cul.CULMode;
import org.osgi.service.cm.ConfigurationException;
import org.osgi.service.cm.ManagedService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
	

/**
 * Implement this class if you are going create an actively polling service
 * like querying a Website/Device.
 * 
 * @author Paul Hampson (cyclingengineer)
 * @since 1.5.0
 */
public class MaxCulBinding extends AbstractActiveBinding<MaxCulBindingProvider> implements ManagedService, CULListener {

	private static final Logger logger = 
		LoggerFactory.getLogger(MaxCulBinding.class);

	
	/** 
	 * the refresh interval which is used to poll values from the MaxCul
	 * server (optional, defaults to 60000ms)
	 */
	private long refreshInterval = 60000;

	/**
	 * The device that is used to access the CUL hardware
	 */
	private String accessDevice;

	/**
	 * This provides access to the CULFW device (e.g. USB stick)
	 */
	private CULHandler cul;

	/**
	 * This sets the address of the controller i.e. us!
	 */
	private String srcAddr = "010203";
	
	/**
	 * Flag to indicate if we are in pairing mode. Default timeout
	 * is 60 seconds.
	 */
	private boolean pairMode = false;
	private int pairModeTimeout = 60000;
	
	private Map<String,Timer> timers = new HashMap<String,Timer>();

	public MaxCulBinding() {
	}

	public void activate() {
		logger.debug("Activating MaxCul binding");
	}

	public void deactivate() {
		// deallocate resources here that are no longer needed and 
		// should be reset when activating this binding again
	}

	/**
	 * @{inheritDoc}
	 */
	@Override
	protected long getRefreshInterval() {
		return refreshInterval;
	}

	/**
	 * @{inheritDoc}
	 */
	@Override
	protected String getName() {
		return "MaxCul Refresh Service";
	}
	
	/**
	 * @{inheritDoc}
	 */
	@Override
	protected void execute() {
		// the frequently executed code (polling) goes here ...
		logger.debug("execute() method is called!");
	}

	/**
	 * @{inheritDoc}
	 */
	@Override
	protected void internalReceiveCommand(final String itemName, Command command) {
		// the code being executed when a command was sent on the openHAB
		// event bus goes here. This method is only called if one of the 
		// BindingProviders provide a binding for the given 'itemName'.
		logger.debug("internalReceiveCommand() is called!");
		Timer timer = null;
		
		MaxCulBindingConfig bindingConfig = null;
		for (MaxCulBindingProvider provider : super.providers) {
			bindingConfig = provider.getConfigForItemName(itemName);
			if (bindingConfig != null) {
				break;
			}
		}
		logger.debug("Received command " + command.toString()
				+ " for item " + itemName);
		if (bindingConfig != null) {
			logger.debug("Found config for "+itemName);
			
			if (bindingConfig.deviceType == MaxCulDevice.PAIR_MODE && (command instanceof OnOffType))
			{
				switch ((OnOffType)command)
				{
					case ON:
						/* turn on pair mode and schedule disabling of pairing mode */
						pairMode = true;
						TimerTask task = new TimerTask() {
                            public void run() {
                            	logger.debug(itemName+" pairMode time out executed");
                                pairMode = false;
                                eventPublisher.postUpdate(itemName, OnOffType.OFF);
                            }
						};
						timer = timers.get(itemName);
						if(timer!=null) {
                            timer.cancel();
                            timers.remove(itemName);
						}
						timer = new Timer();
						timers.put(itemName, timer);
						timer.schedule(task, pairModeTimeout);
						logger.debug(itemName+" pairMode enabled & timeout scheduled");
						break;
					case OFF:
						/* we are manually disabling, so clear the timer and the flag */
						pairMode = false;
						timer = timers.get(itemName);
						if(timer!=null) {
							logger.debug(itemName+" pairMode timer cancelled");
                            timer.cancel();
                            timers.remove(itemName);
						}
						logger.debug(itemName+" pairMode cleared");
						break;
				}
			}
			else if ((bindingConfig.deviceType == MaxCulDevice.RADIATOR_THERMOSTAT ||
					bindingConfig.deviceType == MaxCulDevice.RADIATOR_THERMOSTAT_PLUS ||
					bindingConfig.deviceType == MaxCulDevice.WALL_THERMOSTAT) &&  
					bindingConfig.feature == MaxCulFeature.THERMOSTAT)
			{
				if (command instanceof OnOffType)
				{
					// TODO handle setting thermostat to On or Off
				} else if (command instanceof DecimalType)
				{
					// TODO handle sending temperature to device 
				}
			}
			else logger.warn("Command ignored as it doesn't make sense");
		}
	}
	
	/**
	 * @{inheritDoc}
	 */
	@Override
	public void updated(Dictionary<String, ?> config) throws ConfigurationException {
		if (config != null) {
			
			// to override the default refresh interval one has to add a 
			// parameter to openhab.cfg like <bindingName>:refresh=<intervalInMs>
			String refreshIntervalString = (String) config.get("refresh");
			if (StringUtils.isNotBlank(refreshIntervalString)) {
				refreshInterval = Long.parseLong(refreshIntervalString);
			}
			
			// read further config parameters here ...
			String deviceString = (String) config.get("device");
			if (StringUtils.isNotBlank(deviceString)) {
				setupDevice(deviceString);
				if (cul == null)
					throw new ConfigurationException("device", "Configuration failed. Unable to access CUL device " + deviceString);
			} else {
				setProperlyConfigured(false);
				throw new ConfigurationException("device", "No device set - please set one");
			}
			setProperlyConfigured(true);
		}
	}
	
	private void setupDevice(String device)
	{
		if (cul != null) {
			CULManager.close(cul);
		}
		try {
			accessDevice = device;
			logger.debug("Opening CUL device on " + accessDevice);
			cul = CULManager.getOpenCULHandler(accessDevice, CULMode.MAX);
			cul.registerListener(this);
		} catch (CULDeviceException e) {
			logger.error("Cannot open CUL device", e);
			cul = null;
			accessDevice = null;
		}
	}


	@Override
	public void dataReceived(String data) {
		if (data.startsWith("Z"))
		{
			/* TODO it's a MAX! command so process it */
			logger.debug("Received command "+data);
		}
	}


	@Override
	public void error(Exception e) {
		logger.error("CUL error: "+e.getMessage());
		// TODO add some specific error handling as necessary
	}
}
