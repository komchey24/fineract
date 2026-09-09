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
package org.apache.fineract.infrastructure.dataqueries.service.export;

import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.infrastructure.dataqueries.data.GenericResultsetData;
import org.apache.fineract.infrastructure.dataqueries.service.DatatableExportTargetParameter;
import org.apache.fineract.infrastructure.dataqueries.service.ReadReportingService;
import org.apache.fineract.infrastructure.dataqueries.service.export.pdf.HtmlToPdfConverter;
import org.apache.fineract.infrastructure.dataqueries.service.export.pdf.ReportHtmlRenderService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Exports a stretchy report as a PDF laid out by an HTML template, as opposed to
 * {@link PdfDatatableReportExportService} which draws an unstyled grid straight onto the page.
 *
 * The bean is conditional on {@code fineract.report.pdf.enabled}; when the renderer is not configured the
 * {@code PDF_TEMPLATE} target simply does not appear in {@code /runreports/availableExports/{reportName}} and
 * requesting it fails with the usual "export mode unavailable" error.
 */
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(value = "fineract.report.pdf.enabled", havingValue = "true")
public class TemplatePdfDatatableReportExportService implements DatatableReportExportService {

    private final ReadReportingService readExtraDataAndReportingService;
    private final ReportHtmlRenderService reportHtmlRenderService;
    private final HtmlToPdfConverter htmlToPdfConverter;

    @Override
    public ResponseHolder export(final String reportName, final MultivaluedMap<String, String> queryParams,
            final Map<String, String> reportParams, final String parameterTypeValue) {
        final GenericResultsetData resultset = this.readExtraDataAndReportingService.retrieveGenericResultset(reportName,
                parameterTypeValue, reportParams);

        final ReportHtmlRenderService.RenderedReportHtml html = this.reportHtmlRenderService.render(reportName, resultset, reportParams);
        final byte[] pdf = this.htmlToPdfConverter.convert(html.body(), html.footer(), reportName);

        // a byte[] entity keeps this usable both over REST and from the report mailing job, which needs the bytes
        return new ResponseHolder(Response.Status.OK).contentType("application/pdf")
                .addHeader("Content-Disposition",
                        "attachment;filename=" + DatatableExportUtil.generatePlainExportFileName(255, "pdf", reportName, reportParams))
                .entity(pdf);
    }

    @Override
    public boolean supports(final DatatableExportTargetParameter exportType) {
        return exportType == DatatableExportTargetParameter.PDF_TEMPLATE;
    }
}
