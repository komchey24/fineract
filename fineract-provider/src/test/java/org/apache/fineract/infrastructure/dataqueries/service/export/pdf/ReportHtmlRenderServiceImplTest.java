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
package org.apache.fineract.infrastructure.dataqueries.service.export.pdf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenant;
import org.apache.fineract.infrastructure.core.service.DateUtils;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.infrastructure.core.service.database.DatabaseType;
import org.apache.fineract.infrastructure.dataqueries.data.GenericResultsetData;
import org.apache.fineract.infrastructure.dataqueries.data.ResultsetColumnHeaderData;
import org.apache.fineract.infrastructure.dataqueries.data.ResultsetRowData;
import org.apache.fineract.template.domain.Template;
import org.apache.fineract.template.domain.TemplateRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class ReportHtmlRenderServiceImplTest {

    private final TemplateRepository templateRepository = Mockito.mock(TemplateRepository.class);
    private final ReportHtmlRenderServiceImpl underTest = new ReportHtmlRenderServiceImpl(templateRepository);

    @BeforeEach
    void setUp() {
        ThreadLocalContextUtil.setTenant(new FineractPlatformTenant(1L, "default", "Default", "Asia/Phnom_Penh", null));
    }

    @AfterEach
    void tearDown() {
        ThreadLocalContextUtil.reset();
    }

    private GenericResultsetData resultset() {
        return new GenericResultsetData(
                List.of(ResultsetColumnHeaderData.basic("ឈ្មោះអតិថិជន", "varchar", DatabaseType.POSTGRESQL),
                        ResultsetColumnHeaderData.basic("ប្រាក់ដើម", "decimal", DatabaseType.POSTGRESQL)),
                List.of(ResultsetRowData.create(List.of("សុខ ដារា", "1500.00")), ResultsetRowData.create(List.of("សរុប", "1500.00"))));
    }

    @Test
    void rendersKhmerHeadersAndValuesWithTheBuiltInTemplate() {
        Mockito.doReturn(Optional.empty()).when(templateRepository).findByName("EOD Collection");

        String html = underTest.render("EOD Collection", resultset(), Map.of("${startDate}", "2026-01-01")).body();

        assertTrue(html.contains("ឈ្មោះអតិថិជន"), "Khmer column heading missing");
        assertTrue(html.contains("សុខ ដារា"), "Khmer cell value missing");
        assertTrue(html.contains("startDate: 2026-01-01"), "report parameter missing from the header block");
        assertTrue(html.contains("size: A4 landscape"), "page size rule missing");
        // the decimal column must be flagged so the template can right align it
        assertTrue(html.contains("<td class=\"num\">1500.00</td>"), "decimal column not marked numeric");
        assertFalse(html.contains("<td class=\"num\">សុខ ដារា</td>"), "text column wrongly marked numeric");
    }

    @Test
    void escapesMarkupComingFromReportData() {
        Mockito.doReturn(Optional.empty()).when(templateRepository).findByName("Client Listing");
        GenericResultsetData resultset = new GenericResultsetData(
                List.of(ResultsetColumnHeaderData.basic("name", "varchar", DatabaseType.POSTGRESQL)),
                List.of(ResultsetRowData.create(List.of("<script>alert(1)</script>"))));

        String html = underTest.render("Client Listing", resultset, Map.of()).body();

        assertFalse(html.contains("<script>alert(1)</script>"), "report data was interpolated unescaped");
        assertTrue(html.contains("&lt;script&gt;"), "report data was not HTML escaped");
    }

    @Test
    void prefersTheStoredTemplateOverTheBuiltInOne() {
        Template template = new Template().setName("EOD Collection").setText("<html><body>{{reportName}}:{{rowCount}}</body></html>");
        Mockito.doReturn(Optional.of(template)).when(templateRepository).findByName("EOD Collection");

        String html = underTest.render("EOD Collection", resultset(), Map.of()).body();

        assertEquals("<html><body>EOD Collection:2</body></html>", html);
    }

    @Test
    void rendersAnEmptyResultsetWithoutATable() {
        Mockito.doReturn(Optional.empty()).when(templateRepository).findByName("EOD Collection");

        String html = underTest.render("EOD Collection",
                new GenericResultsetData(List.of(ResultsetColumnHeaderData.basic("name", "varchar", DatabaseType.POSTGRESQL)), List.of()),
                Map.of()).body();

        assertTrue(html.contains("No data"), "empty state missing");
        assertFalse(html.contains("<tbody>"), "a table was rendered for an empty resultset");
    }

    @Test
    void footerCarriesPageNumberPlaceholdersForChromium() {
        Mockito.doReturn(Optional.empty()).when(templateRepository).findByName("EOD Collection");

        String footer = underTest.render("EOD Collection", resultset(), Map.of()).footer();

        assertTrue(footer.contains("class=\"pageNumber\""), "page number placeholder missing");
        assertTrue(footer.contains("class=\"totalPages\""), "total pages placeholder missing");
        assertTrue(footer.contains("EOD Collection"), "report name missing from the footer");
    }

    @Test
    void generatedAtUsesTheTenantClock() {
        Mockito.doReturn(Optional.empty()).when(templateRepository).findByName("EOD Collection");

        String html = underTest.render("EOD Collection", resultset(), Map.of()).body();

        assertTrue(html.contains("generated " + DateUtils.getOffsetDateTimeOfTenant().toLocalDate()), "tenant date missing");
    }
}
