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
package org.apache.fineract.portfolio.loanaccount.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import java.time.LocalDate;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

@ExtendWith(MockitoExtension.class)
class LoanReadPlatformServiceImplRepaymentsTest {

    private static final LocalDate DATE = LocalDate.of(2026, 11, 17);
    private static final int REPAYMENT_TYPE = 2;

    @Mock
    private JdbcTemplate jdbcTemplate;
    @Mock
    private PlatformSecurityContext context;
    @InjectMocks
    private LoanReadPlatformServiceImpl service;

    @Test
    void filtersByDateAndOfficeOnlyWhenNoOfficerIsGiven() {
        service.retrieveRepayments(DATE, null);

        final ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(sql.capture(), any(RowMapper.class), eq(REPAYMENT_TYPE), eq(DATE), eq(".%"));
        assertTrue(sql.getValue().contains("t.is_reversed = false"));
        assertFalse(sql.getValue().contains("l.loan_officer_id = ?"));
    }

    @Test
    void addsTheOfficerFilterWhenGiven() {
        service.retrieveRepayments(DATE, 3L);

        final ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(sql.capture(), any(RowMapper.class), eq(REPAYMENT_TYPE), eq(DATE), eq(".%"), eq(3L));
        assertTrue(sql.getValue().contains("l.loan_officer_id = ?"));
    }
}
