/*
 * Copyright (c) 2008-2015 EMC Corporation
 * All Rights Reserved
 */
package com.emc.storageos.api.service.impl.resource.blockingestorchestration;

import java.net.URI;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.emc.storageos.api.service.impl.resource.utils.PropertySetterUtil;
import com.emc.storageos.api.service.impl.resource.utils.VolumeIngestionUtil;
import com.emc.storageos.db.client.DbClient;
import com.emc.storageos.db.client.model.BlockConsistencyGroup;
import com.emc.storageos.db.client.model.BlockObject;
import com.emc.storageos.db.client.model.DataObject;
import com.emc.storageos.db.client.model.Project;
import com.emc.storageos.db.client.model.StoragePool;
import com.emc.storageos.db.client.model.StorageSystem;
import com.emc.storageos.db.client.model.StringSet;
import com.emc.storageos.db.client.model.TenantOrg;
import com.emc.storageos.db.client.model.VirtualArray;
import com.emc.storageos.db.client.model.VirtualPool;
import com.emc.storageos.db.client.model.Volume;
import com.emc.storageos.db.client.model.UnManagedDiscoveredObjects.UnManagedVolume;
import com.emc.storageos.db.client.model.UnManagedDiscoveredObjects.UnManagedVolume.SupportedVolumeInformation;
import com.emc.storageos.db.client.model.util.BlockConsistencyGroupUtils;

/**
 * Responsible for ingesting vplex local and distributed virtual volumes.
 */
public class BlockVplexVolumeIngestOrchestrator extends BlockVolumeIngestOrchestrator {
    private static final Logger _logger = LoggerFactory.getLogger(BlockVplexVolumeIngestOrchestrator.class);

    private static final long CACHE_TIMEOUT = 600000; // ten minutes
    private long cacheLastRefreshed = 0;

    // maps the cluster id (1 or 2) to its name (e.g., cluster-1 or cluster-2)
    private Map<String, String> clusterIdToNameMap = new HashMap<String, String>();

    // maps each virtual array's URI to the cluster id (1 or 2) it connects to
    private Map<String, String> varrayToClusterIdMap = new HashMap<String, String>();

    @Override
    public <T extends BlockObject> T ingestBlockObjects(List<URI> systemCache, List<URI> poolCache, StorageSystem system,
            UnManagedVolume unManagedVolume,
            VirtualPool vPool, VirtualArray virtualArray, Project project, TenantOrg tenant,
            List<UnManagedVolume> unManagedVolumesToBeDeleted,
            Map<String, BlockObject> createdObjectMap, Map<String, List<DataObject>> updatedObjectMap, boolean unManagedVolumeExported,
            Class<T> clazz,
            Map<String, StringBuffer> taskStatusMap) throws IngestionException {
        // For VPLEX volumes, verify that it is OK to ingest the unmanaged
        // volume into the requested virtual array.

        long timeRightNow = new Date().getTime();
        if (timeRightNow > (cacheLastRefreshed + CACHE_TIMEOUT)) {
            _logger.debug("clearing vplex ingestion api info cache");
            clusterIdToNameMap.clear();
            varrayToClusterIdMap.clear();
            cacheLastRefreshed = timeRightNow;
        }

        if (!VolumeIngestionUtil.isValidVarrayForUnmanagedVolume(unManagedVolume, virtualArray.getId(),
                clusterIdToNameMap, varrayToClusterIdMap, _dbClient)) {
            _logger.warn("UnManaged Volume {} cannot be ingested into the requested varray. Skipping Ingestion.",
                    unManagedVolume.getLabel());

            throw IngestionException.exceptions.varrayIsInvalidForVplexVolume(virtualArray.getLabel(), unManagedVolume.getLabel());
        }

        return super.ingestBlockObjects(systemCache, poolCache, system, unManagedVolume, vPool, virtualArray, project, tenant,
                unManagedVolumesToBeDeleted, createdObjectMap, updatedObjectMap, unManagedVolumeExported, clazz, taskStatusMap);
    }

    @Override
    protected void updateBlockObjectNativeIds(BlockObject blockObject, UnManagedVolume unManagedVolume) {
        String label = unManagedVolume.getLabel();
        blockObject.setDeviceLabel(label);
        blockObject.setLabel(label);
        blockObject.setNativeId(blockObject.getNativeGuid());
    }

    @Override
    protected URI getConsistencyGroupUri(UnManagedVolume unManagedVolume, VirtualPool vPool, URI project, URI tenant,
            URI virtualArray, DbClient dbClient) {
        return VolumeIngestionUtil.getVplexConsistencyGroup(unManagedVolume, vPool, project, tenant, virtualArray, dbClient);
    }

    @Override
    protected void updateCGPropertiesInVolume(URI consistencyGroupUri, Volume volume, StorageSystem system,
            UnManagedVolume unManagedVolume) {
        if (consistencyGroupUri != null) {

            String cgName = PropertySetterUtil.extractValueFromStringSet(
                    SupportedVolumeInformation.VPLEX_CONSISTENCY_GROUP_NAME.toString(), unManagedVolume.getVolumeInformation());

            BlockConsistencyGroup cg = _dbClient.queryObject(BlockConsistencyGroup.class, consistencyGroupUri);

            StringSet unmanagedVolumeClusters = unManagedVolume.getVolumeInformation().get(
                    SupportedVolumeInformation.VPLEX_CLUSTER_IDS.toString());
            // Add a ViPR CG mapping for each of the VPlex clusters the VPlex CG
            // belongs to.
            if (unmanagedVolumeClusters != null && !unmanagedVolumeClusters.isEmpty()) {
                Iterator<String> unmanagedVolumeClustersItr = unmanagedVolumeClusters.iterator();
                while (unmanagedVolumeClustersItr.hasNext()) {
                    cg.addSystemConsistencyGroup(system.getId().toString(),
                            BlockConsistencyGroupUtils.buildClusterCgName(unmanagedVolumeClustersItr.next(), cgName));
                }

                _dbClient.updateAndReindexObject(cg);
            }

            volume.setConsistencyGroup(consistencyGroupUri);
        }
    }

    @Override
    protected void setProtocol(StoragePool pool, Volume volume, VirtualPool vPool) {
        if (null == volume.getProtocol()) {
            volume.setProtocol(new StringSet());
        }
        volume.getProtocol().addAll(vPool.getProtocols());
    }

    @Override
    protected StoragePool validateAndReturnStoragePoolInVAarray(UnManagedVolume unManagedVolume, VirtualArray virtualArray) {
        return null;
    }

    @Override
    protected void checkSystemResourceLimitsExceeded(StorageSystem system, UnManagedVolume unManagedVolume, List<URI> systemCache) {
        // always return true,as for vplex volumes this limit doesn't have any effect
        return;
    }

    @Override
    protected void checkPoolResourceLimitsExceeded(StorageSystem system, StoragePool pool, UnManagedVolume unManagedVolume,
            List<URI> poolCache) {
        // always return true, as pool will be null
        return;
    }

    @Override
    protected void checkUnManagedVolumeAddedToCG(UnManagedVolume unManagedVolume, VirtualArray virtualArray, TenantOrg tenant,
            Project project, VirtualPool vPool) {
        if (VolumeIngestionUtil.checkUnManagedResourceAddedToConsistencyGroup(unManagedVolume)) {
            URI consistencyGroupUri = VolumeIngestionUtil.getVplexConsistencyGroup(unManagedVolume, vPool, project.getId(),
                    tenant.getId(), virtualArray.getId(), _dbClient);
            if (null == consistencyGroupUri) {
                _logger.warn("A Consistency Group for the VPLEX volume could not be determined. Skipping Ingestion.");
                throw IngestionException.exceptions.unmanagedVolumeVplexConsistencyGroupCouldNotBeIdentified(unManagedVolume.getLabel());
            }

        }
    }

    @Override
    protected void validateAutoTierPolicy(String autoTierPolicyId, UnManagedVolume unManagedVolume, VirtualPool vPool) {
        super.validateAutoTierPolicy(autoTierPolicyId, unManagedVolume, vPool);
    }
}
