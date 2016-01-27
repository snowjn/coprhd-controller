package com.emc.sa.service.vipr.migration;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.emc.sa.engine.ExecutionUtils;
import com.emc.sa.engine.bind.Param;
import com.emc.sa.engine.service.Service;
import com.emc.sa.service.ServiceParams;
import com.emc.sa.service.vipr.ViPRService;
import com.emc.sa.service.vipr.block.tasks.GetMobilityGroup;
import com.emc.sa.service.vipr.block.tasks.GetMobilityGroupChildren;
import com.emc.sa.service.vipr.block.tasks.GetMobilityGroupClusters;
import com.emc.sa.service.vipr.block.tasks.GetMobilityGroupHosts;
import com.emc.sa.service.vipr.block.tasks.GetMobilityGroupVolumes;
import com.emc.sa.service.vipr.block.tasks.GetMobilityGroupVolumesByCluster;
import com.emc.sa.service.vipr.block.tasks.GetMobilityGroupVolumesByHost;
import com.emc.sa.service.vipr.block.tasks.GetUnmanagedVolumesByHostOrCluster;
import com.emc.sa.service.vipr.block.tasks.MigrateBlockVolumes;
import com.emc.sa.service.vipr.block.tasks.RemoveVolumeFromMobilityGroup;
import com.emc.sa.service.vipr.compute.ComputeUtils;
import com.emc.storageos.db.client.model.VolumeGroup;
import com.emc.storageos.model.NamedRelatedResourceRep;
import com.emc.storageos.model.application.VolumeGroupRestRep;
import com.emc.storageos.model.block.NamedVolumeGroupsList;
import com.emc.storageos.model.block.VolumeRestRep;
import com.emc.vipr.client.Task;
import com.emc.vipr.client.Tasks;
import com.emc.vipr.client.exceptions.ServiceErrorException;
import com.emc.vipr.client.exceptions.TimeoutException;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;

@Service("MobilityGroupMigration")
public class MobilityGroupMigrationService extends ViPRService {

    @Param(ServiceParams.MOBILITY_GROUP)
    private URI mobilityGroupId;

    @Param(ServiceParams.TARGET_VIRTUAL_POOL)
    private URI targetVirtualPool;

    @Param(ServiceParams.TARGET_STORAGE_SYSTEM)
    private URI targetStorageSystem;

    @Param(value = ServiceParams.INGEST_VOLUMES, required = false)
    private Boolean ingestVolumes;

    @Param(value = ServiceParams.PROJECT, required = false)
    private URI project;

    private VolumeGroupRestRep mobilityGroup;

    @Override
    public void precheck() throws Exception {
        mobilityGroup = execute(new GetMobilityGroup(mobilityGroupId));
    }

    @Override
    public void execute() throws Exception {

        if (ingestVolumes != null && ingestVolumes) {
            // TODO ingest volumes
            ingestVolumes();
        }

        List<Task<VolumeRestRep>> tasks = new ArrayList<>();

        for (URI volume : getVolumes()) {
            try {
                Task<VolumeRestRep> migrationTask = execute(new MigrateBlockVolumes(volume, mobilityGroup.getSourceStorageSystem(),
                        targetVirtualPool, targetStorageSystem));
                tasks.add(migrationTask);
            } catch (ServiceErrorException ex) {
                ExecutionUtils.currentContext().logError(ex.getDetailedMessage());
            }
        }

        if (tasks.isEmpty()) {
            ExecutionUtils.fail("failTask.mobilityGroupMigration.noVolumesMigrated", new Object[] {}, new Object[] {});
        }

        while (!tasks.isEmpty()) {
            waitAndRefresh(tasks);
            for (Task<VolumeRestRep> successfulTask : ComputeUtils.getSuccessfulTasks(tasks)) {
                URI volumeId = successfulTask.getResourceId();
                addAffectedResource(volumeId);
                tasks.remove(successfulTask);
                if (mobilityGroup.getMigrationGroupBy().equalsIgnoreCase(VolumeGroup.MigrationGroupBy.VOLUMES.name())) {
                    execute(new RemoveVolumeFromMobilityGroup(mobilityGroup.getId(), volumeId));
                }
            }
            for (Task<VolumeRestRep> failedTask : ComputeUtils.getFailedTasks(tasks)) {
                String errorMessage = failedTask.getMessage() == null ? "" : failedTask.getMessage();
                ExecutionUtils.currentContext().logError("computeutils.exportbootvolumes.failure",
                        failedTask.getResource().getName(), errorMessage);
                tasks.remove(failedTask);
            }
        }
    }

    private static <T> void waitAndRefresh(List<Task<T>> tasks) {
        long t = 100;  // >0 to keep waitFor(t) from waiting until task completes
        for (Task<T> task : tasks) {
            try {
                task.waitFor(t); // internal polling interval overrides (typically ~10 secs)
            } catch (TimeoutException te) {
                // ignore timeout after polling interval
            } catch (Exception e) {
                ExecutionUtils.currentContext().logError("computeutils.task.exception", e.getMessage());
            }
        }
    }

    private void ingestVolumes() {
        // String ingestionMethod = IngestionMethodEnum.FULL.toString();
        List<NamedRelatedResourceRep> hostsOrClusters = Lists.newArrayList();
        if (mobilityGroup.getMigrationGroupBy().equals(VolumeGroup.MigrationGroupBy.HOSTS.name())) {
            hostsOrClusters = execute(new GetMobilityGroupHosts(mobilityGroup.getId()));
        } else if (mobilityGroup.getMigrationGroupBy().equals(VolumeGroup.MigrationGroupBy.CLUSTERS.name())) {
            hostsOrClusters = execute(new GetMobilityGroupClusters(mobilityGroup.getId()));
        } else {
            // TODO fail
        }

        for (NamedRelatedResourceRep hostOrCluster : hostsOrClusters) {
            int remaining = execute(new GetUnmanagedVolumesByHostOrCluster(
                    hostOrCluster.getId())).size();

            logInfo("ingest.exported.unmanaged.volume.service.remaining", remaining);
        }

        //
        // int succeed = execute(new IngestExportedUnmanagedVolumes(virtualPool, virtualArray, project,
        // host == null ? null : host.getId(),
        // cluster == null ? null : cluster.getId(),
        // uris(volumeIds),
        // ingestionMethod
        // )).getTasks().size();
        // logInfo("ingest.exported.unmanaged.volume.service.ingested", succeed);
        // logInfo("ingest.exported.unmanaged.volume.service.skipped", volumeIds.size() - succeed);

    }

    private List<URI> getVolumeList(Tasks<VolumeRestRep> tasks) {
        List<URI> volumes = Lists.newArrayList();
        for (Task<VolumeRestRep> task : tasks.getTasks()) {

            if (task.getResourceId() != null) {
                volumes.add(task.getResourceId());
            }
        }
        return volumes;
    }

    private Set<URI> getVolumes() {
        if (mobilityGroup.getMigrationGroupBy().equalsIgnoreCase(VolumeGroup.MigrationGroupBy.VOLUMES.name())) {
            return execute(new GetMobilityGroupVolumes(Lists.newArrayList(mobilityGroupId)));
        } else if (mobilityGroup.getMigrationGroupBy().equalsIgnoreCase(VolumeGroup.MigrationGroupBy.HOSTS.name())) {
            List<NamedRelatedResourceRep> hosts = execute(new GetMobilityGroupHosts(mobilityGroupId));
            return execute(new GetMobilityGroupVolumesByHost(mobilityGroup, hosts));
        } else if (mobilityGroup.getMigrationGroupBy().equalsIgnoreCase(VolumeGroup.MigrationGroupBy.CLUSTERS.name())) {
            List<NamedRelatedResourceRep> clusters = execute(new GetMobilityGroupClusters(mobilityGroupId));
            return execute(new GetMobilityGroupVolumesByCluster(mobilityGroup, clusters));
        } else if (mobilityGroup.getMigrationGroupBy().equalsIgnoreCase(VolumeGroup.MigrationGroupBy.APPLICATIONS.name())) {
            NamedVolumeGroupsList children = execute(new GetMobilityGroupChildren(mobilityGroupId));
            return execute(new GetMobilityGroupVolumes(children));
        }
        return Sets.newHashSet();
    }
}
