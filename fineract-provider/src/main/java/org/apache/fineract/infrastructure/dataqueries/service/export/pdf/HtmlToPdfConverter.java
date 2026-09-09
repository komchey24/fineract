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

/**
 * Converts a rendered HTML document into PDF bytes.
 *
 * Kept deliberately narrow so the rendering back end (currently a Gotenberg / headless Chromium service) can be swapped
 * without touching the report export services. A browser based renderer is used rather than an in-process Java PDF
 * library because complex scripts such as Khmer require OpenType GSUB shaping, which the Base-14 font path of
 * {@code org.openpdf} does not perform.
 */
public interface HtmlToPdfConverter {

    /**
     * @param html
     *            the complete HTML document to render
     * @param footerHtml
     *            optional footer document rendered on every page, may be null
     * @param documentName
     *            name used for logging and diagnostics only
     * @return the rendered PDF bytes
     */
    byte[] convert(String html, String footerHtml, String documentName);
}
