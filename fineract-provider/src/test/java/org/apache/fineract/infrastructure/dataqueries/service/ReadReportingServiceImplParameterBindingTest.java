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

package org.apache.fineract.infrastructure.dataqueries.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Types;
import java.util.List;
import java.util.Map;
import org.apache.fineract.infrastructure.core.config.FineractProperties;
import org.apache.fineract.infrastructure.core.service.database.DatabaseSpecificSQLGenerator;
import org.apache.fineract.infrastructure.core.service.database.DatabaseTypeResolver;
import org.apache.fineract.infrastructure.dataqueries.data.GenericResultsetData;
import org.apache.fineract.infrastructure.dataqueries.exception.ReportNotFoundException;
import org.apache.fineract.infrastructure.report.service.ReportParameterTypeResolver;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.infrastructure.security.service.SqlInjectionPreventerService;
import org.apache.fineract.organisation.office.domain.Office;
import org.apache.fineract.useradministration.domain.AppUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.stubbing.Answer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.SqlParameterValue;
import org.springframework.jdbc.support.rowset.SqlRowSet;

/**
 * Covers how report parameters are bound into the prepared statement, in particular the parameter-type lookups
 * (<code>?parameterType=true</code>) that carry no registered format type because their names live in
 * <code>stretchy_parameter</code> rather than <code>stretchy_report</code>.
 */
class ReadReportingServiceImplParameterBindingTest {

    /**
     * The stored SQL of the seeded 'loanOfficerIdSelectAll' parameter, which compares against the BIGINT m_office.id.
     */
    private static final String LOAN_OFFICER_SQL = "(select lo.id, lo.display_name AS name from m_office o "
            + "join m_office ounder on ounder.hierarchy like concat(o.hierarchy, '%') " + "join m_staff lo on lo.office_id = ounder.id "
            + "where lo.is_loan_officer = true and o.id = '${officeId}') union all (select -10, '-') order by 2";

    private JdbcTemplate jdbcTemplate;
    private PlatformSecurityContext context;
    private GenericDataService genericDataService;
    private SqlInjectionPreventerService sqlInjectionPreventerService;
    private DatabaseSpecificSQLGenerator sqlGenerator;
    private ReportParameterTypeResolver reportParameterTypeResolver;
    private DatabaseTypeResolver databaseTypeResolver;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        context = mock(PlatformSecurityContext.class);
        genericDataService = mock(GenericDataService.class);
        sqlInjectionPreventerService = mock(SqlInjectionPreventerService.class);
        sqlGenerator = mock(DatabaseSpecificSQLGenerator.class);
        reportParameterTypeResolver = mock(ReportParameterTypeResolver.class);
        databaseTypeResolver = mock(DatabaseTypeResolver.class);

        final Office office = mock(Office.class);
        when(office.getHierarchy()).thenReturn(".");
        final AppUser user = mock(AppUser.class);
        when(user.getOffice()).thenReturn(office);
        when(user.getId()).thenReturn(1L);
        when(context.authenticatedUser()).thenReturn(user);

        when(sqlInjectionPreventerService.quoteIdentifier(anyString())).thenAnswer((Answer<String>) i -> i.getArgument(0));
        when(sqlGenerator.currentBusinessDate()).thenReturn("current_date");
        when(sqlGenerator.currentTenantDateTime()).thenReturn("now()");
        when(genericDataService.replace(anyString(), anyString(), anyString()))
                .thenAnswer((Answer<String>) i -> i.<String>getArgument(0).replace(i.getArgument(1), i.getArgument(2)));
        when(genericDataService.wrapSQL(anyString())).thenAnswer((Answer<String>) i -> "select x.* from (" + i.getArgument(0) + ") x");
        when(genericDataService.fillGenericResultSet(anyString(), org.mockito.ArgumentMatchers.<Object[]>any()))
                .thenReturn(new GenericResultsetData(List.of(), List.of()));
    }

    private ReadReportingServiceImpl service() {
        return new ReadReportingServiceImpl(jdbcTemplate, context, genericDataService, sqlInjectionPreventerService, sqlGenerator,
                mock(FineractProperties.class), reportParameterTypeResolver, databaseTypeResolver);
    }

    /** Makes getSql() return the given stored SQL for the report/parameter being run. */
    private void storedSqlIs(final String storedSql) {
        final SqlRowSet rowSet = mock(SqlRowSet.class);
        when(rowSet.next()).thenReturn(true);
        when(rowSet.getString("the_sql")).thenReturn(storedSql);
        when(jdbcTemplate.queryForRowSet(anyString(), eq("loanOfficerIdSelectAll"))).thenReturn(rowSet);
    }

    private Object[] captureBoundArgs() {
        final ArgumentCaptor<Object[]> captor = ArgumentCaptor.forClass(Object[].class);
        verify(genericDataService).fillGenericResultSet(anyString(), captor.capture());
        return captor.getValue();
    }

    @Test
    void unregisteredParameterIsBoundAsUntypedOnPostgreSql() {
        // A parameter-type lookup resolves no format types, because its name is not a stretchy_report name.
        when(reportParameterTypeResolver.loadParamFormatTypes("loanOfficerIdSelectAll")).thenReturn(Map.of());
        when(databaseTypeResolver.isPostgreSQL()).thenReturn(true);
        storedSqlIs(LOAN_OFFICER_SQL);

        service().retrieveGenericResultset("loanOfficerIdSelectAll", "parameter", Map.of("${officeId}", "1"));

        final Object[] args = captureBoundArgs();
        assertEquals(1, args.length, "Exactly one placeholder should be bound");
        final SqlParameterValue bound = assertInstanceOf(SqlParameterValue.class, args[0],
                "An unregistered parameter must be bound as an untyped value so PostgreSQL can coerce it to bigint");
        assertEquals(Types.OTHER, bound.getSqlType(), "Wrong JDBC type");
        assertEquals("1", bound.getValue(), "Wrong bound value");
    }

    @Test
    void unregisteredParameterIsBoundAsStringOnMySql() {
        // MySQL/MariaDB coerce varchar to numeric implicitly and their driver rejects Types.OTHER for a String.
        when(reportParameterTypeResolver.loadParamFormatTypes("loanOfficerIdSelectAll")).thenReturn(Map.of());
        when(databaseTypeResolver.isPostgreSQL()).thenReturn(false);
        storedSqlIs(LOAN_OFFICER_SQL);

        service().retrieveGenericResultset("loanOfficerIdSelectAll", "parameter", Map.of("${officeId}", "1"));

        assertEquals("1", captureBoundArgs()[0], "MySQL should keep the plain String binding");
    }

    @Test
    void registeredNumericParameterIsStillBoundAsLong() {
        // Reports that do resolve a format type must keep their existing strongly typed binding.
        when(reportParameterTypeResolver.loadParamFormatTypes("loanOfficerIdSelectAll")).thenReturn(Map.of("officeId", "number"));
        when(databaseTypeResolver.isPostgreSQL()).thenReturn(true);
        storedSqlIs(LOAN_OFFICER_SQL);

        service().retrieveGenericResultset("loanOfficerIdSelectAll", "parameter", Map.of("${officeId}", "1"));

        assertEquals(1L, captureBoundArgs()[0], "A registered 'number' parameter should bind as a Long");
    }

    @Test
    void placeholderReportSqlIsTreatedAsMissing() {
        // 'FullReportList' and 'ReportCategoryList' were seeded with the literal string '(NULL)' as their SQL.
        when(reportParameterTypeResolver.loadParamFormatTypes(anyString())).thenReturn(Map.of());
        storedSqlIs("(NULL)");

        assertThrows(ReportNotFoundException.class,
                () -> service().retrieveGenericResultset("loanOfficerIdSelectAll", "report", Map.of()),
                "A '(NULL)' placeholder SQL must report the report as missing, not be executed");
    }
}
