/*
 * Copyright (c) 2013 EMC Corporation
 * All Rights Reserved
 */
package com.emc.storageos.api.service.impl.resource;

import static org.apache.commons.lang.StringUtils.EMPTY;

import java.net.URI;

import org.junit.Assert;
import org.junit.Test;

import com.emc.storageos.db.client.model.DataObject;
import com.emc.storageos.db.client.model.StorageSystem;
import com.emc.storageos.svcs.errorhandling.resources.APIException;
import com.emc.storageos.svcs.errorhandling.resources.BadRequestException;
import com.emc.storageos.svcs.errorhandling.resources.NotFoundException;
import com.emc.storageos.svcs.errorhandling.resources.ServiceCode;

public class ArgValidatorTest extends Assert {

    @Test
    public void testCheckValidUri(){
        ArgValidator.checkUri(URI.create("urn:storageos:StorageSystem:2b91947d-749f-4356-aad7-dcd7f7906197:vdc1"));
    }

    @Test(expected = APIException.class)
    public void testCheckUriBadScheme() {
        try {
            ArgValidator.checkUri(URI.create("other:storageos:StorageSystem:2b91947d-749f-4356-aad7-dcd7f7906197:vdc1"));
        } catch (APIException apiException) {
            assertEquals(ServiceCode.API_PARAMETER_INVALID_URI, apiException.getServiceCode());
            assertEquals("Parameter other:storageos:StorageSystem:2b91947d-749f-4356-aad7-dcd7f7906197:vdc1 is not a valid URI", apiException.getLocalizedMessage());
            throw apiException;
        }
    }

    @Test(expected = APIException.class)
    public void testCheckUriBadSchemeSpecificPart() {
        try {
            ArgValidator.checkUri(URI.create("urn:other:StorageSystem:2b91947d-749f-4356-aad7-dcd7f7906197:vdc1"));
        } catch (APIException apiException) {
            assertEquals(ServiceCode.API_PARAMETER_INVALID_URI, apiException.getServiceCode());
            assertEquals("Parameter urn:other:StorageSystem:2b91947d-749f-4356-aad7-dcd7f7906197:vdc1 is not a valid URI", apiException.getLocalizedMessage());
            throw apiException;
        }
    }

    @Test(expected = APIException.class)
    public void testCheckEmptyUri() {
        try {
            ArgValidator.checkUri(URI.create(EMPTY));
        } catch (APIException apiException) {
            assertEquals(ServiceCode.API_PARAMETER_INVALID_URI, apiException.getServiceCode());
            assertEquals("Parameter  is not a valid URI", apiException.getLocalizedMessage());
            throw apiException;
        }
    }

    @Test(expected = APIException.class)
    public void testCheckNullUri() {
        try {
            ArgValidator.checkUri(null);
        } catch (APIException apiException) {
            assertEquals(ServiceCode.API_PARAMETER_INVALID_URI, apiException.getServiceCode());
            assertEquals("Parameter null is not a valid URI", apiException.getLocalizedMessage());
            throw apiException;
        }
    }

    @Test
    public void testCheckFieldNotNullPositiveCase() {
        final Object mockObject = new Object();
        ArgValidator.checkFieldNotNull(mockObject, "mock");
    }

    @Test(expected = BadRequestException.class)
    public void testCheckFieldNotNullNegativeCase() {
        try {
            ArgValidator.checkFieldNotNull(null, "mock");
        } catch (BadRequestException e) {
            assertEquals(ServiceCode.API_PARAMETER_MISSING, e.getServiceCode());
            assertEquals("Required parameter mock was missing or empty", e.getLocalizedMessage());
            throw e;
        }
    }

    @Test
    public void testCheckEntityPositiveCase() {
        ArgValidator.checkEntity(new StorageSystem(), URI.create("urn:storageos:StorageSystem:2b91947d-749f-4356-aad7-dcd7f7906197:vdc"),
                false);
    }

    @Test(expected = NotFoundException.class)
    public void testCheckEntityNegativeCase() {
        ArgValidator.checkEntity(null, URI.create("urn:storageos:StorageSystem:2b91947d-749f-4356-aad7-dcd7f7906197:vdc"), true);
    }

    @Test(expected = BadRequestException.class)
    public void testCheckEntityInactiveEntityBadRequest() {
        try {
            DataObject object = new DataObject(){};
            object.setId(URI.create("urn:storageos:StorageSystem:2b91947d-749f-4356-aad7-dcd7f7906197:vdc1"));
            object.setInactive(true);
            ArgValidator.checkEntity(object, object.getId(), false);
        } catch (APIException bre) {
            assertEquals(ServiceCode.API_PARAMETER_INACTIVE, bre.getServiceCode());
            assertEquals("Entity with the given id urn:storageos:StorageSystem:2b91947d-749f-4356-aad7-dcd7f7906197:vdc1 is inactive and marked for deletion", 
                    bre.getLocalizedMessage());
            throw bre;
        }
    }

    @Test(expected = NotFoundException.class)
    public void testCheckEntityInactiveEntityNotfound() {
        try {
            DataObject object = new DataObject(){};
            object.setId(URI.create("urn:storageos:StorageSystem:2b91947d-749f-4356-aad7-dcd7f7906197:vdc1"));
            object.setInactive(true);
            ArgValidator.checkEntity(object, object.getId(), true);
        } catch (APIException bre) {
            assertEquals(ServiceCode.API_URL_ENTITY_INACTIVE, bre.getServiceCode());
            assertEquals("Entity specified in URL with the given id urn:storageos:StorageSystem:2b91947d-749f-4356-aad7-dcd7f7906197:vdc1 is inactive and marked for deletion", 
                    bre.getLocalizedMessage());
            throw bre;
        }
    }
}
