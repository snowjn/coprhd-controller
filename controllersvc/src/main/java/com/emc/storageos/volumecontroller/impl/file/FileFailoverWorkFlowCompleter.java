package com.emc.storageos.volumecontroller.impl.file;

import java.net.URI;
import java.util.List;

import com.emc.storageos.db.client.DbClient;
import com.emc.storageos.db.client.model.Operation;
import com.emc.storageos.svcs.errorhandling.model.ServiceCoded;

public class FileFailoverWorkFlowCompleter extends FileWorkflowCompleter {
    private static final long serialVersionUID = -494348560407624019L;

    public FileFailoverWorkFlowCompleter(List<URI> fsUris, String task) {
        super(fsUris, task);
    }

    public FileFailoverWorkFlowCompleter(URI fileUri, String task) {
        super(fileUri, task);
    }

    @Override
    protected void complete(DbClient dbClient, Operation.Status status, ServiceCoded serviceCoded) {
        super.complete(dbClient, status, serviceCoded);
    }

}
