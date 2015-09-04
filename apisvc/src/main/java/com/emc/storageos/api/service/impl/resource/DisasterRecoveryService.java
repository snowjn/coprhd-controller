/*
 * Copyright (c) 2008-2015 EMC Corporation
 * All Rights Reserved
 */
package com.emc.storageos.api.service.impl.resource;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Iterator;
import java.util.List;

import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.emc.storageos.api.mapper.SiteMapper;
import com.emc.storageos.db.client.URIUtil;
import com.emc.storageos.db.client.model.Site;
import com.emc.storageos.db.client.model.StringMap;
import com.emc.storageos.db.client.model.StringSet;
import com.emc.storageos.db.client.model.VirtualDataCenter;
import com.emc.storageos.model.ResourceTypeEnum;
import com.emc.storageos.model.dr.SiteAddParam;
import com.emc.storageos.model.dr.SiteList;
import com.emc.storageos.model.dr.SiteRestRep;
import com.emc.storageos.security.authorization.DefaultPermissions;
import com.emc.storageos.security.authorization.Role;

@Path("/site")
@DefaultPermissions(readRoles = { Role.SYSTEM_ADMIN, Role.RESTRICTED_SYSTEM_ADMIN },
        writeRoles = { Role.SYSTEM_ADMIN, Role.RESTRICTED_SYSTEM_ADMIN })
public class DisasterRecoveryService extends TaggedResource {

    private static final Logger log = LoggerFactory.getLogger(DisasterRecoveryService.class);

    public DisasterRecoveryService() {

    }

    @POST
    @Consumes({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
    @Produces({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
    public SiteRestRep addStandby(SiteAddParam param) {
        log.info("Begin to add standby site");

        Site standbySite = new Site();
        standbySite.setId(URIUtil.createId(Site.class));
        standbySite.setUuid(param.getUuid());
        standbySite.setName(param.getName());
        standbySite.setVip(param.getVip());
        standbySite.getHostIPv4AddressMap().putAll(new StringMap(param.getHostIPv4AddressMap()));
        standbySite.getHostIPv6AddressMap().putAll(new StringMap(param.getHostIPv6AddressMap()));

        if (log.isDebugEnabled()) {
            log.debug(standbySite.toString());
        }
        
        VirtualDataCenter vdc = queryLocalVDC();

        if (vdc.getStandbyIDs() == null) {
            vdc.setStandbyIDs(new StringSet());
        }

        vdc.getStandbyIDs().add(standbySite.getId().toString());

        log.info("Persist standby site to DB");
        _dbClient.createObject(standbySite);
        
        log.info("Update VCD to persist new standby site ID");
        _dbClient.persistObject(vdc);

        return SiteMapper.map(standbySite);
    }

    @GET
    @Produces({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
    public SiteList getAllStandby() {
        log.info("Begin to list all standby sites of local VDC");
        SiteList standbyList = new SiteList();

        VirtualDataCenter vdc = queryLocalVDC();

        try {
            for (String id : vdc.getStandbyIDs()) {
                standbyList.getSites().add(SiteMapper.map(queryResource(new URI(id))));
            }
        } catch (URISyntaxException e) {
            log.error("Failed to construct site object ID {}", e);
        }
        
        return standbyList;
    }

    @GET
    @Produces({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
    @Path("/{id}")
    public SiteRestRep getStandby(@PathParam("id") String id) {
        log.info("Begin to get standby site by uuid");
        
        VirtualDataCenter vdc = queryLocalVDC();
        
        List<URI> ids = _dbClient.queryByType(Site.class, true);
        Iterator<Site> iter = _dbClient.queryIterativeObjects(Site.class, ids);
        while (iter.hasNext()) {
            Site standby = iter.next();
            if (vdc.getStandbyIDs().contains(standby.getId().toString())) {
                if (standby.getUuid().equals(id)) {
                    return SiteMapper.map(standby);
                }
            }
        }

        return null;
    }

    @DELETE
    @Produces({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
    @Path("/{id}")
    public SiteRestRep removeStandby(@PathParam("id") String id) {
        log.info("Begin to remove standby site from local vdc");
        
        VirtualDataCenter vdc = queryLocalVDC();
        
        List<URI> ids = _dbClient.queryByType(Site.class, true);
        Iterator<Site> iter = _dbClient.queryIterativeObjects(Site.class, ids);
        while (iter.hasNext()) {
            Site standby = iter.next();
            if (vdc.getStandbyIDs().contains(standby.getId().toString())) {
                if (standby.getUuid().equals(id)) {
                    log.info("Find standby site in local VDC and remove it");
                    _dbClient.persistObject(vdc);
                    _dbClient.markForDeletion(standby);
                    return SiteMapper.map(standby);
                }
            }
        }
        
        return null;
    }

    @Override
    protected Site queryResource(URI id) {
        ArgValidator.checkUri(id);
        Site standby = _dbClient.queryObject(Site.class, id);
        ArgValidator.checkEntityNotNull(standby, id, isIdEmbeddedInURL(id));
        return standby;
    }

    @Override
    protected URI getTenantOwner(URI id) {
        return null;
    }

    @Override
    protected ResourceTypeEnum getResourceType() {
        return ResourceTypeEnum.STANDBY;
    }

    private VirtualDataCenter queryLocalVDC() {
        List<URI> ids = _dbClient.queryByType(VirtualDataCenter.class, true);
        Iterator<VirtualDataCenter> iter = _dbClient.queryIterativeObjects(VirtualDataCenter.class, ids);
        while (iter.hasNext()) {
            VirtualDataCenter vdc = iter.next();
            if (vdc.getLocal()) {
                log.info("find local vdc instance");
                return vdc;
            }
        }

        return null;
    }
}
