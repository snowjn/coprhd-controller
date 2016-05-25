/*
 * Copyright 2016 Intel Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */
package com.emc.storageos.model.keystone;

import javax.xml.bind.annotation.*;
import java.util.ArrayList;
import java.util.List;

@XmlRootElement(name = "coprhd_os_tenant_list")
@XmlAccessorType(XmlAccessType.PROPERTY)
public class CoprhdOsTenantListRestRep {

    private List<CoprhdOsTenant> coprhd_os_tenants;

    @XmlElementRef
    public List<CoprhdOsTenant> getCoprhd_os_tenants() {
        if (coprhd_os_tenants == null) {
            coprhd_os_tenants = new ArrayList<>();
        }
        return coprhd_os_tenants;
    }

    public void setCoprhd_os_tenants(List<CoprhdOsTenant> coprhd_os_tenants) {
        this.coprhd_os_tenants = coprhd_os_tenants;
    }
}
