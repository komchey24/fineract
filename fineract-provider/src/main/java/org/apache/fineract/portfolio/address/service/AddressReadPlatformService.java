/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.fineract.portfolio.address.service;

import java.util.List;
import org.apache.fineract.portfolio.address.data.AddressData;
import org.apache.fineract.portfolio.address.data.AddressSuggestionData;
import org.apache.fineract.portfolio.address.filter.ClientAddressSearchParam;

public interface AddressReadPlatformService {

    List<AddressData> retrieveAddressFields(long clientid);

    List<AddressData> retrieveAllClientAddress(long clientid);

    List<AddressData> retrieveAddressbyType(long clientid, long typeid);

    List<AddressData> retrieveAddressbyTypeAndStatus(long clientid, long typeid, String status);

    List<AddressData> retrieveAddressbyStatus(long clientid, String status);

    List<AddressData> retrieveBySearchParam(ClientAddressSearchParam searchFilter);

    AddressData retrieveTemplate();

    /**
     * Returns distinct existing address hierarchies whose address line 1 (village) starts with the given prefix.
     *
     * @param prefix
     *            address line 1 prefix; blank returns an empty list
     * @param limit
     *            maximum number of rows, defaults to 10 and is capped at 50
     */
    List<AddressSuggestionData> searchByAddressLine1Prefix(String prefix, Integer limit);
}
