/*
 * Copyright (c) 2016 EMC
 * All Rights Reserved
 */
package com.emc.sa.service.vipr.file.tasks;

import java.net.URI;

import com.emc.sa.service.vipr.tasks.WaitForTasks;
import com.emc.storageos.model.file.FileCopy;
import com.emc.storageos.model.file.FileReplicationParam;
import com.emc.storageos.model.file.FileShareRestRep;
import com.emc.vipr.client.Tasks;

public class StopFileContinuousCopy extends WaitForTasks<FileShareRestRep> {
    private URI fileId;
    private URI continuousCopyId;
    private String type;

    public StopFileContinuousCopy(URI fileId, URI continuousCopyId, String type) {
        this.continuousCopyId = continuousCopyId;
        this.fileId = fileId;
        this.type = type;
        provideDetailArgs(fileId, continuousCopyId, type);
    }

    @Override
    protected Tasks<FileShareRestRep> doExecute() throws Exception {
        FileCopy copy = new FileCopy();
        copy.setCopyID(continuousCopyId);
        copy.setType(type);

        FileReplicationParam param = new FileReplicationParam();
        param.getCopies().add(copy);
        return getClient().fileSystems().stopFileContinuousCopies(fileId, param);
    }
}
