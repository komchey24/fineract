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

import java.util.Map;
import org.apache.fineract.infrastructure.dataqueries.data.GenericResultsetData;

/**
 * Renders the resultset of a stretchy report into an HTML document using a Mustache template.
 */
public interface ReportHtmlRenderService {

    /**
     * Renders the report page and its repeating footer from a single template scope.
     *
     * The template used for the page is the {@code m_template} row whose name equals {@code reportName}, falling back
     * to the built-in generic table template when no such row exists.
     */
    RenderedReportHtml render(String reportName, GenericResultsetData resultset, Map<String, String> reportParams);

    record RenderedReportHtml(String body, String footer) {
    }
}
