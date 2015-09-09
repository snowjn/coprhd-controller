package com.emc.storageos.volumecontroller.impl;

import java.net.URI;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.emc.storageos.Controller;
import com.emc.storageos.db.client.DbClient;
import com.emc.storageos.db.client.model.DiscoveredDataObject.Type;
import com.emc.storageos.db.client.model.Bucket;
import com.emc.storageos.db.client.model.DiscoveredSystemObject;
import com.emc.storageos.db.client.model.StorageSystem;
import com.emc.storageos.exceptions.ClientControllerException;
import com.emc.storageos.impl.AbstractDiscoveredSystemController;
import com.emc.storageos.model.object.BucketParam;
import com.emc.storageos.svcs.errorhandling.resources.InternalException;
import com.emc.storageos.volumecontroller.AsyncTask;
import com.emc.storageos.volumecontroller.ObjectController;
import com.emc.storageos.volumecontroller.impl.ControllerServiceImpl.Lock;
import com.emc.storageos.volumecontroller.impl.monitoring.MonitoringJob;
import com.emc.storageos.volumecontroller.impl.plugins.discovery.smis.MonitorTaskCompleter;

public class ObjectControllerImpl extends AbstractDiscoveredSystemController
		implements ObjectController {
	 private final static Logger _log = LoggerFactory.getLogger(FileControllerImpl.class);

    // device specific ObjectController implementations
    private Set<ObjectController> _deviceImpl;
    private Dispatcher _dispatcher;
    private DbClient _dbClient;

    public void setDeviceImpl(Set<ObjectController> deviceImpl) {
        _deviceImpl = deviceImpl;
    }

    public void setDispatcher(Dispatcher dispatcher) {
        _dispatcher = dispatcher;
    }

    public void setDbClient(DbClient dbClient) {
        _dbClient = dbClient;
    }
    
	@Override
	public void connectStorage(URI storage) throws InternalException {
		_log.info("ObjectControllerImpl:connectStorage");
		 execOb("connectStorage", storage);

	}

	@Override
	public void disconnectStorage(URI storage) throws InternalException {
		execOb("disconnectStorage", storage);

	}

	@Override
	public void discoverStorageSystem(AsyncTask[] tasks)
			throws InternalException {
		_log.info("ObjectControllerImpl:discoverStorageSystem");
        try {
            ControllerServiceImpl.scheduleDiscoverJobs(tasks, Lock.DISCOVER_COLLECTION_LOCK, ControllerServiceImpl.DISCOVERY);
        } catch (Exception e) {
            _log.error(
                    "Problem in discoverStorageSystem due to {} ",
                    e.getMessage());
            throw ClientControllerException.fatals.unableToScheduleDiscoverJobs(tasks, e);
        }

	}

	@Override
	public void scanStorageProviders(AsyncTask[] tasks)
			throws InternalException {
		_log.info("ObjectControllerImpl:scanStorageProviders");
		 throw ClientControllerException.fatals.unableToScanSMISProviders(tasks, "ObjectController", null);

	}

	@Override
	public void startMonitoring(AsyncTask task, Type deviceType)
			throws InternalException {
        try {
        	_log.info("ObjectControllerImpl:startMonitoring");
            MonitoringJob job = new MonitoringJob();
            job.setCompleter(new MonitorTaskCompleter(task));
            job.setDeviceType(deviceType);
            ControllerServiceImpl.enqueueMonitoringJob(job);
        } catch (Exception e) {
            throw ClientControllerException.fatals.unableToMonitorSMISProvider(task, deviceType.toString(), e);
        }
	}

	@Override
	public Controller lookupDeviceController(DiscoveredSystemObject device) {
        // dummy impl that returns the first one
		_log.info("ObjectControllerImpl:lookupDeviceController");
        return _deviceImpl.iterator().next();	
        }
	
    private void execOb(String methodName, Object... args) throws InternalException {
    	StringBuilder logMsgBuilder = new StringBuilder(String.format(
                "ObjectControllerImpl Method=%s StorageSystem.class:%s", methodName, StorageSystem.class));
        queueTask(_dbClient, StorageSystem.class, _dispatcher, methodName, args);
    }

	@Override
	public void createBucket(URI storage, URI vPool, URI bkt, String label, String namespace, String retention,
			String hardQuota, String softQuota, String owner, String opId) throws InternalException {
		// TODO Auto-generated method stub
		_log.info("ObjectControllerImpl:createBukcet start");
		execOb("createBucket", storage, vPool, bkt, label, namespace, retention,
				hardQuota, softQuota, owner, opId);
		_log.info("ObjectControllerImpl:createBukcet end");
	}

    @Override
    public void deleteBucket(URI storage, URI bucket, String task) throws InternalException {
        _log.info("ObjectControllerImpl:deleteBukcet");
        execOb("deleteBucket", storage, bucket, task);
    }

    @Override
    public void updateBucket(URI storage, URI bucket, Long softQuota, Long hardQuota, Integer retention, String task) throws InternalException {
        _log.info("ObjectControllerImpl:updateBukcet");
        execOb("updateBucket", storage, bucket, softQuota, hardQuota, retention, task);
    }
}