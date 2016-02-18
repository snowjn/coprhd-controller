/*
 * Copyright (c) 2015 EMC
 * All Rights Reserved
 */
package com.emc.sa.service.vipr.application.tasks;

import java.net.URI;

import com.emc.sa.service.vipr.tasks.WaitForTasks;
import com.emc.storageos.model.TaskList;
import com.emc.storageos.model.TaskResourceRep;
import com.emc.storageos.model.application.VolumeGroupSnapshotSessionDeactivateParam;
import com.emc.vipr.client.Tasks;
import com.google.common.collect.Lists;

public class DeleteSnapshotSessionForApplication extends WaitForTasks<TaskResourceRep> {
    private final URI applicationId;
    private final URI volume;

    public DeleteSnapshotSessionForApplication(URI applicationId, URI volume) {
        this.applicationId = applicationId;
        this.volume = volume;
        provideDetailArgs(applicationId);
    }

    @Override
    protected Tasks<TaskResourceRep> doExecute() throws Exception {
        VolumeGroupSnapshotSessionDeactivateParam input = new VolumeGroupSnapshotSessionDeactivateParam();
        input.setPartial(true);
        input.setSnapshotSessions(Lists.newArrayList(volume));

        TaskList taskList = getClient().application().deactivateApplicationSnapshotSession(applicationId, input);

        return new Tasks<TaskResourceRep>(getClient().auth().getClient(), taskList.getTaskList(),
                TaskResourceRep.class);
    }
}