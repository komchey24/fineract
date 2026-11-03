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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import org.apache.fineract.infrastructure.codes.service.CodeValueReadPlatformService;
import org.apache.fineract.infrastructure.core.service.database.DatabaseSpecificSQLGenerator;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.portfolio.address.data.AddressSuggestionData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

@ExtendWith(MockitoExtension.class)
class AddressReadPlatformServiceImplTest {

    @Mock
    private JdbcTemplate jdbcTemplate;
    @Mock
    private PlatformSecurityContext context;
    @Mock
    private CodeValueReadPlatformService codeValueReadPlatformService;
    @Mock
    private DatabaseSpecificSQLGenerator sqlGenerator;

    private AddressReadPlatformServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new AddressReadPlatformServiceImpl(jdbcTemplate, context, codeValueReadPlatformService, sqlGenerator);
    }

    @Test
    void escapeLikeWildcardsMatchesInputLiterally() {
        assertEquals("50\\%\\_off\\\\x", AddressReadPlatformServiceImpl.escapeLikeWildcards("50%_off\\x"));
        assertEquals("ភូមិ", AddressReadPlatformServiceImpl.escapeLikeWildcards("ភូមិ"));
    }

    @Test
    void clampSearchLimitDefaultsAndCaps() {
        assertEquals(AddressReadPlatformServiceImpl.DEFAULT_SEARCH_LIMIT, AddressReadPlatformServiceImpl.clampSearchLimit(null));
        assertEquals(AddressReadPlatformServiceImpl.DEFAULT_SEARCH_LIMIT, AddressReadPlatformServiceImpl.clampSearchLimit(0));
        assertEquals(5, AddressReadPlatformServiceImpl.clampSearchLimit(5));
        assertEquals(AddressReadPlatformServiceImpl.MAX_SEARCH_LIMIT, AddressReadPlatformServiceImpl.clampSearchLimit(1000));
    }

    @Test
    void blankPrefixReturnsEmptyWithoutQuerying() {
        assertTrue(service.searchByAddressLine1Prefix("   ", 10).isEmpty());
        assertTrue(service.searchByAddressLine1Prefix(null, 10).isEmpty());
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    @SuppressWarnings("unchecked")
    void searchBindsEscapedTrimmedPrefixAsParameter() {
        final AddressSuggestionData row = new AddressSuggestionData("ភូមិ ក", "ឃុំ", "ស្រុក", null, null, 12L, "Province", 3L, "Cambodia");
        when(sqlGenerator.limit(10)).thenReturn("LIMIT 10 OFFSET 0");
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq("ភូ\\%%"))).thenReturn(List.of(row));

        final List<AddressSuggestionData> result = service.searchByAddressLine1Prefix("  ភូ%  ", null);

        assertEquals(List.of(row), result);
        verify(jdbcTemplate).query(contains("where addr.address_line_1 like ?"), any(RowMapper.class), eq("ភូ\\%%"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void rowMapperReadsIntegerIdColumnsAndNulls() throws SQLException {
        when(sqlGenerator.limit(10)).thenReturn("LIMIT 10 OFFSET 0");
        service.searchByAddressLine1Prefix("ក", 10);
        final ArgumentCaptor<RowMapper<AddressSuggestionData>> mapperCaptor = ArgumentCaptor.forClass(RowMapper.class);
        verify(jdbcTemplate).query(anyString(), mapperCaptor.capture(), eq("ក%"));

        // m_address.state_province_id / country_id are INT columns: the driver must not be asked for getObject(Long.class)
        final ResultSet rs = mock(ResultSet.class);
        when(rs.getString("address_line_1")).thenReturn("ក");
        when(rs.findColumn("state_province_id")).thenReturn(6);
        when(rs.findColumn("country_id")).thenReturn(8);
        when(rs.getLong(6)).thenReturn(12L);
        when(rs.getLong(8)).thenReturn(0L);
        when(rs.wasNull()).thenReturn(false, true);

        final AddressSuggestionData data = mapperCaptor.getValue().mapRow(rs, 0);

        assertEquals("ក", data.addressLine1());
        assertEquals(12L, data.stateProvinceId());
        assertNull(data.countryId());
    }
}
