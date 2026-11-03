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
package org.apache.fineract.portfolio.address.data;

import java.io.Serial;
import java.io.Serializable;

/**
 * A distinct existing address hierarchy (village, commune, district, province, country) used to pre-fill a new client
 * address. House-specific fields (street, city/house number, coordinates) and postal code are intentionally excluded.
 */
public record AddressSuggestionData(String addressLine1, String addressLine2, String addressLine3, String townVillage,
        String countyDistrict, Long stateProvinceId, String stateName, Long countryId, String countryName) implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;
}
