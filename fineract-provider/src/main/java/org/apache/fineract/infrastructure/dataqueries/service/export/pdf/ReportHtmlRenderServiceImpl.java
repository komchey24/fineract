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

import com.github.mustachejava.DefaultMustacheFactory;
import com.github.mustachejava.Mustache;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.infrastructure.core.service.DateUtils;
import org.apache.fineract.infrastructure.dataqueries.data.GenericResultsetData;
import org.apache.fineract.infrastructure.dataqueries.data.ResultsetColumnHeaderData;
import org.apache.fineract.infrastructure.dataqueries.data.ResultsetRowData;
import org.apache.fineract.template.domain.Template;
import org.apache.fineract.template.domain.TemplateRepository;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReportHtmlRenderServiceImpl implements ReportHtmlRenderService {

    private static final String DEFAULT_BODY_TEMPLATE = "templates/report/default-report.mustache";
    private static final String DEFAULT_FOOTER_TEMPLATE = "templates/report/default-footer.mustache";
    private static final DateTimeFormatter GENERATED_AT_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final TemplateRepository templateRepository;

    @Override
    public RenderedReportHtml render(final String reportName, final GenericResultsetData resultset,
            final Map<String, String> reportParams) {
        final Map<String, Object> scope = buildScope(reportName, resultset, reportParams);

        final String bodyTemplate = templateRepository.findByName(reportName) //
                .map(Template::getText) //
                .orElseGet(() -> readClasspathTemplate(DEFAULT_BODY_TEMPLATE));

        return new RenderedReportHtml(compile(bodyTemplate, reportName, scope),
                compile(readClasspathTemplate(DEFAULT_FOOTER_TEMPLATE), reportName + " footer", scope));
    }

    /**
     * Builds the scope every report template is rendered against. Column and cell entries carry a {@code numeric} flag
     * so that a template can right-align figures without knowing anything about the underlying SQL.
     */
    private Map<String, Object> buildScope(final String reportName, final GenericResultsetData resultset,
            final Map<String, String> reportParams) {
        final List<ResultsetColumnHeaderData> columnHeaders = resultset.getColumnHeaders();

        final List<Map<String, Object>> columns = new ArrayList<>(columnHeaders.size());
        final List<Boolean> numericColumn = new ArrayList<>(columnHeaders.size());
        for (final ResultsetColumnHeaderData columnHeader : columnHeaders) {
            final boolean numeric = columnHeader.isDecimalDisplayType() || columnHeader.isIntegerDisplayType();
            numericColumn.add(numeric);
            columns.add(Map.of("name", columnHeader.getColumnName(), "numeric", numeric));
        }

        final List<ResultsetRowData> data = resultset.getData();
        final List<Map<String, Object>> rows = new ArrayList<>(data.size());
        int rowNumber = 0;
        for (final ResultsetRowData rowData : data) {
            final List<Object> values = rowData.getRow();
            final List<Map<String, Object>> cells = new ArrayList<>(values.size());
            for (int i = 0; i < values.size(); i++) {
                final Object value = values.get(i);
                final boolean numeric = i < numericColumn.size() && numericColumn.get(i);
                cells.add(Map.of("value", value == null ? "" : Objects.toString(value), "numeric", numeric));
            }
            rowNumber++;
            rows.add(Map.of("cells", cells, "rowNumber", rowNumber, "odd", rowNumber % 2 == 1));
        }

        final List<Map<String, Object>> params = new ArrayList<>();
        final Map<String, Object> paramValues = new HashMap<>();
        if (reportParams != null) {
            new LinkedHashMap<>(reportParams).forEach((key, value) -> {
                final String name = stripPlaceholder(key);
                params.add(Map.of("name", name, "value", value == null ? "" : value));
                paramValues.put(name, value == null ? "" : value);
            });
            params.sort((left, right) -> Objects.toString(left.get("name")).compareTo(Objects.toString(right.get("name"))));
        }

        final Map<String, Object> scope = new HashMap<>();
        scope.put("reportName", reportName);
        scope.put("generatedAt", DateUtils.getOffsetDateTimeOfTenant().format(GENERATED_AT_FORMAT));
        scope.put("columns", columns);
        scope.put("columnCount", columns.size());
        scope.put("rows", rows);
        scope.put("rowCount", rows.size());
        scope.put("hasRows", !rows.isEmpty());
        scope.put("params", params);
        scope.put("paramValues", paramValues);
        return scope;
    }

    /**
     * Report values reach the template as text and are interpolated with {@code {{ }}}, which Mustache HTML-escapes, so
     * data holding markup cannot break out of the cell it belongs to.
     */
    private String compile(final String templateText, final String templateName, final Map<String, Object> scope) {
        final Mustache mustache = new DefaultMustacheFactory().compile(new StringReader(templateText), templateName);
        final StringWriter writer = new StringWriter();
        mustache.execute(writer, scope);
        return writer.toString();
    }

    private String readClasspathTemplate(final String location) {
        try {
            return new String(new ClassPathResource(location).getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (final IOException e) {
            throw new UncheckedIOException("Could not read the built-in report template " + location, e);
        }
    }

    private String stripPlaceholder(final String key) {
        return key != null && key.startsWith("${") && key.endsWith("}") ? key.substring(2, key.length() - 1) : key;
    }
}
