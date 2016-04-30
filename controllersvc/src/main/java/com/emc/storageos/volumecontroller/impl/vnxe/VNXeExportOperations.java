/*
 * Copyright (c) 2014 EMC Corporation
 * All Rights Reserved
 */

package com.emc.storageos.volumecontroller.impl.vnxe;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.emc.storageos.db.client.URIUtil;
import com.emc.storageos.db.client.model.BlockObject;
import com.emc.storageos.db.client.model.BlockSnapshot;
import com.emc.storageos.db.client.model.ExportGroup;
import com.emc.storageos.db.client.model.ExportMask;
import com.emc.storageos.db.client.model.HostInterface.Protocol;
import com.emc.storageos.db.client.model.Initiator;
import com.emc.storageos.db.client.model.StorageSystem;
import com.emc.storageos.db.client.model.StringSet;
import com.emc.storageos.db.client.model.VirtualPool;
import com.emc.storageos.db.client.model.Volume;
import com.emc.storageos.exceptions.DeviceControllerErrors;
import com.emc.storageos.exceptions.DeviceControllerException;
import com.emc.storageos.svcs.errorhandling.model.ServiceError;
import com.emc.storageos.vnxe.VNXeApiClient;
import com.emc.storageos.vnxe.models.VNXeExportResult;
import com.emc.storageos.vnxe.models.VNXeHostInitiator;
import com.emc.storageos.vnxe.models.VNXeLunSnap;
import com.emc.storageos.volumecontroller.TaskCompleter;
import com.emc.storageos.volumecontroller.impl.VolumeURIHLU;
import com.emc.storageos.volumecontroller.impl.smis.ExportMaskOperations;
import com.emc.storageos.volumecontroller.impl.utils.ExportMaskUtils;
import com.google.common.base.Joiner;

public class VNXeExportOperations extends VNXeOperations implements ExportMaskOperations {
    private static final Logger _logger = LoggerFactory.getLogger(VNXeExportOperations.class);

    @Override
    public void createExportMask(StorageSystem storage, URI exportMask,
            VolumeURIHLU[] volumeURIHLUs, List<URI> targetURIList,
            List<Initiator> initiatorList, TaskCompleter taskCompleter)
            throws DeviceControllerException {
        _logger.info("{} createExportMask START...", storage.getSerialNumber());

        VNXeApiClient apiClient = getVnxeClient(storage);
        try {
            _logger.info("createExportMask: Export mask id: {}", exportMask);
            _logger.info("createExportMask: volume-HLU pairs: {}", Joiner.on(',').join(volumeURIHLUs));
            _logger.info("createExportMask: initiators: {}", Joiner.on(',').join(initiatorList));
            _logger.info("createExportMask: assignments: {}", Joiner.on(',').join(targetURIList));
            
            List<VNXeHostInitiator> initiators = prepareInitiators(initiatorList);
            ExportMask mask = _dbClient.queryObject(ExportMask.class, exportMask);
            for (VolumeURIHLU volURIHLU : volumeURIHLUs) {
                URI volUri = volURIHLU.getVolumeURI();
                BlockObject blockObject = BlockObject.fetch(_dbClient, volUri);
                String nativeId = blockObject.getNativeId();
                if (URIUtil.isType(volUri, Volume.class)) {
                    VNXeExportResult result = apiClient.exportLun(nativeId, initiators);
                    mask.addVolume(volUri, result.getHlu());
                } else if (URIUtil.isType(volUri, BlockSnapshot.class)) {
                    VNXeExportResult result = apiClient.exportSnap(nativeId, initiators);
                    setSnapWWN(apiClient, blockObject, nativeId);
                    mask.addVolume(volUri, result.getHlu());
                }

            }
            _dbClient.updateObject(mask);
            taskCompleter.ready(_dbClient);

        } catch (Exception e) {
            _logger.error("Unexpected error: createExportMask failed.", e);
            ServiceError error = DeviceControllerErrors.vnxe.jobFailed("createExportMask", e.getMessage());
            taskCompleter.error(_dbClient, error);
        }
        _logger.info("{} createExportMask END...", storage.getSerialNumber());
    }

    private List<VNXeHostInitiator> prepareInitiators(List<Initiator> initiators) {
        List<VNXeHostInitiator> result = new ArrayList<VNXeHostInitiator>();
        for (Initiator init : initiators) {
            _logger.info("initiator: {}", init.getId().toString());
            VNXeHostInitiator hostInit = new VNXeHostInitiator();
            hostInit.setName(init.getHostName());
            String protocol = init.getProtocol();
            if (protocol.equalsIgnoreCase(Protocol.iSCSI.name())) {
                hostInit.setType(VNXeHostInitiator.HostInitiatorTypeEnum.INITIATOR_TYPE_ISCSI);
                hostInit.setChapUserName(init.getInitiatorPort());
                hostInit.setInitiatorId(init.getInitiatorPort());

            } else if (protocol.equalsIgnoreCase(Protocol.FC.name())) {
                hostInit.setType(VNXeHostInitiator.HostInitiatorTypeEnum.INITIATOR_TYPE_FC);
                String portWWN = init.getInitiatorPort();
                String nodeWWN = init.getInitiatorNode();
                StringBuilder builder = new StringBuilder(nodeWWN);
                builder.append(":");
                builder.append(portWWN);
                hostInit.setInitiatorId(builder.toString());
                hostInit.setNodeWWN(nodeWWN);
                hostInit.setPortWWN(portWWN);
            } else {
                _logger.info("The initiator {} protocol {} is not supported, skip",
                        init.getId(), init.getProtocol());
                continue;
            }

            hostInit.setName(init.getHostName());
            result.add(hostInit);

        }
        return result;
    }

    @Override
    public void deleteExportMask(StorageSystem storage, URI exportMaskUri,
            List<URI> volumeURIList, List<URI> targetURIList,
            List<Initiator> initiatorList, TaskCompleter taskCompleter)
            throws DeviceControllerException {
        _logger.info("{} deleteExportMask START...", storage.getSerialNumber());

        try {
            _logger.info("Export mask id: {}", exportMaskUri);
            // TODO DUPP:
            // 1. Get the volume, targets, and initiators from the caller
            // 2. Ensure (if possible) that those are the only volumes/initiators impacted by delete mask
            if (volumeURIList != null) {
                _logger.info("deleteExportMask: volumes:  {}", Joiner.on(',').join(volumeURIList));
            }
            if (targetURIList != null) {
                _logger.info("deleteExportMask: assignments: {}", Joiner.on(',').join(targetURIList));
            }
            if (initiatorList != null) {
                _logger.info("deleteExportMask: initiators: {}", Joiner.on(',').join(initiatorList));
            }
            
            VNXeApiClient apiClient = getVnxeClient(storage);
            ExportMask exportMask = _dbClient.queryObject(ExportMask.class, exportMaskUri);
            StringSet inits = exportMask.getInitiators();
            for (String init : inits) {
                _logger.info("Initiator: {}", init);
                Initiator initiator = _dbClient.queryObject(Initiator.class, URI.create(init));
                initiatorList.add(initiator);
            }

            List<VNXeHostInitiator> initiators = prepareInitiators(initiatorList);
            for (URI volUri : volumeURIList) {
                BlockObject blockObject = BlockObject.fetch(_dbClient, volUri);
                String nativeId = blockObject.getNativeId();
                if (URIUtil.isType(volUri, Volume.class)) {
                    apiClient.unexportLun(nativeId, initiators);
                } else if (URIUtil.isType(volUri, BlockSnapshot.class)) {
                    apiClient.unexportSnap(nativeId, initiators);
                    setSnapWWN(apiClient, blockObject, nativeId);
                }
                // update the exportMask object
                exportMask.removeVolume(volUri);
            }

            _dbClient.updateObject(exportMask);

            List<ExportGroup> exportGroups = ExportMaskUtils.getExportGroups(_dbClient, exportMask);
            if (exportGroups != null) {
                // Remove the mask references in the export group
                for (ExportGroup exportGroup : exportGroups) {
                    // Remove this mask from the export group
                    exportGroup.removeExportMask(exportMask.getId().toString());
                }
                // Update all of the export groups in the DB
                _dbClient.updateObject(exportGroups);
            }

            taskCompleter.ready(_dbClient);
        } catch (Exception e) {
            _logger.error("Unexpected error: deleteExportMask failed.", e);
            ServiceError error = DeviceControllerErrors.vnxe.jobFailed("deleteExportMask", e.getMessage());
            taskCompleter.error(_dbClient, error);
        }
        _logger.info("{} deleteExportMask END...", storage.getSerialNumber());
    }

    @Override
    public void addVolumes(StorageSystem storage, URI exportMaskUri,
            VolumeURIHLU[] volumeURIHLUs, List<Initiator> initiatorList, TaskCompleter taskCompleter)
            throws DeviceControllerException {
        _logger.info("{} addVolume START...", storage.getSerialNumber());
        try {
            _logger.info("addVolumes: Export mask id: {}", exportMaskUri);
            _logger.info("addVolumes: volume-HLU pairs: {}", Joiner.on(',').join(volumeURIHLUs));
            // TODO DUPP:
            // 1. Get initiator list from the caller above for completeness
            // 2. If possible, log if these volumes are going to be exported to additional initiators than what the request asked for
            if (initiatorList != null) {
                _logger.info("addVolumes: initiators impacted: {}", Joiner.on(',').join(initiatorList));
            }
            
            VNXeApiClient apiClient = getVnxeClient(storage);
            ExportMask exportMask = _dbClient.queryObject(ExportMask.class, exportMaskUri);
            StringSet initiatorUris = exportMask.getInitiators();
            List<Initiator> initiators = new ArrayList<Initiator>();
            for (String initiatorUri : initiatorUris) {
                Initiator init = _dbClient.queryObject(Initiator.class, URI.create(initiatorUri));
                initiators.add(init);
            }
            List<VNXeHostInitiator> vnxeInitiators = prepareInitiators(initiators);

            for (VolumeURIHLU volURIHLU : volumeURIHLUs) {
                URI volUri = volURIHLU.getVolumeURI();
                BlockObject blockObject = BlockObject.fetch(_dbClient, volUri);
                String nativeId = blockObject.getNativeId();
                if (URIUtil.isType(volUri, Volume.class)) {
                    VNXeExportResult result = apiClient.exportLun(nativeId, vnxeInitiators);
                    exportMask.addVolume(volUri, result.getHlu());
                } else if (URIUtil.isType(volUri, BlockSnapshot.class)) {
                    VNXeExportResult result = apiClient.exportSnap(nativeId, vnxeInitiators);
                    exportMask.addVolume(volUri, result.getHlu());
                    setSnapWWN(apiClient, blockObject, nativeId);
                }

            }
            _dbClient.updateObject(exportMask);
            taskCompleter.ready(_dbClient);

        } catch (Exception e) {
            _logger.error("Add volumes error: ", e);
            ServiceError error = DeviceControllerErrors.vnxe.jobFailed("addVolume", e.getMessage());
            taskCompleter.error(_dbClient, error);
        }
        _logger.info("{} addVolumes END...", storage.getSerialNumber());
    }

    @Override
    public void removeVolumes(StorageSystem storage, URI exportMaskUri,
            List<URI> volumes, List<Initiator> initiatorList, TaskCompleter taskCompleter)
            throws DeviceControllerException {
        _logger.info("{} removeVolumes: START...", storage.getSerialNumber());

        try {
            _logger.info("removeVolumes: Export mask id: {}", exportMaskUri);
            _logger.info("removeVolumes: volumes: {}", Joiner.on(',').join(volumes));
            // TODO DUPP:
            // 1. Get initiator list from the caller
            // 2. Verify that the initiators are the ONLY ones impacted by this remove volumes, otherwise fail.
            // 
            // This implementation is pulling the initiators directly out of the export mask because the caller to detach 
            //    the volumes needs this information.  This might be OK to do separately than verifying the initiators that are
            //    impacted from the orchestrator, although it is likely they will be the same list.
            //
            if (initiatorList != null) {
                _logger.info("removeVolumes: impacted initiators: {}", Joiner.on(",").join(initiatorList));
            }
            
            VNXeApiClient apiClient = getVnxeClient(storage);
            ExportMask exportMask = _dbClient.queryObject(ExportMask.class, exportMaskUri);
            StringSet initiatorUris = exportMask.getInitiators();
            List<Initiator> initiators = new ArrayList<Initiator>();
            for (String initiatorUri : initiatorUris) {
                Initiator init = _dbClient.queryObject(Initiator.class, URI.create(initiatorUri));
                initiators.add(init);
            }
            List<VNXeHostInitiator> vnxeInitiators = prepareInitiators(initiators);
            for (URI volUri : volumes) {
                BlockObject blockObject = BlockObject.fetch(_dbClient, volUri);
                String nativeId = blockObject.getNativeId();
                if (URIUtil.isType(volUri, Volume.class)) {
                    apiClient.unexportLun(nativeId, vnxeInitiators);
                } else if (URIUtil.isType(volUri, BlockSnapshot.class)) {
                    apiClient.unexportSnap(nativeId, vnxeInitiators);
                    setSnapWWN(apiClient, blockObject, nativeId);
                }
                // update the exportMask object
                exportMask.removeVolume(volUri);
            }

            _dbClient.updateObject(exportMask);

            taskCompleter.ready(_dbClient);
        } catch (Exception e) {
            _logger.error("Unexpected error: removeVolumes failed.", e);
            ServiceError error = DeviceControllerErrors.vnxe.jobFailed("remove volumes failed", e.getMessage());
            taskCompleter.error(_dbClient, error);
        }
        _logger.info("{} removeVolumes END...", storage.getSerialNumber());
    }

    @Override
    public void addInitiators(StorageSystem storage, URI exportMaskUri,
            List<URI> volumeURIs, List<Initiator> initiatorList,
            List<URI> targets, TaskCompleter taskCompleter) throws DeviceControllerException {

        _logger.info("{} addInitiator START...", storage.getSerialNumber());

    }

    @Override
    public void removeInitiators(StorageSystem storage, URI exportMask,
            List<URI> volumeURIList, List<Initiator> initiators,
            List<URI> targets, TaskCompleter taskCompleter) throws DeviceControllerException {
        // TODO Auto-generated method stub

    }

    @Override
    public Map<String, Set<URI>> findExportMasks(StorageSystem storage,
            List<String> initiatorNames, boolean mustHaveAllPorts) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public ExportMask refreshExportMask(StorageSystem storage, ExportMask mask) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public void updateStorageGroupPolicyAndLimits(StorageSystem storage, ExportMask exportMask,
            List<URI> volumeURIs, VirtualPool newVirtualPool, boolean rollback,
            TaskCompleter taskCompleter) throws Exception {
        throw DeviceControllerException.exceptions.blockDeviceOperationNotSupported();
    }

    @Override
    public Map<URI, Integer> getExportMaskHLUs(StorageSystem storage, ExportMask exportMask) {
        return Collections.emptyMap();
    }

    /**
     * set snap wwn after export/unexport. if a snap is not exported to any host, its wwn is null
     * 
     * @param apiClient
     * @param blockObj
     * @param snapId
     */
    private void setSnapWWN(VNXeApiClient apiClient, BlockObject blockObj, String snapId) {

        VNXeLunSnap snap = apiClient.getLunSnapshot(snapId);
        String wwn = snap.getPromotedWWN();
        if (wwn == null) {
            wwn = "";
        }
        blockObj.setWWN(wwn);
        _dbClient.persistObject(blockObj);
    }

}
