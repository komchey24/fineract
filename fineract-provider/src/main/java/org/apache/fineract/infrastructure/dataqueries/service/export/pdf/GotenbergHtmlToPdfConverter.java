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

import java.io.IOException;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.apache.commons.lang3.StringUtils;
import org.apache.fineract.infrastructure.core.config.FineractProperties;
import org.apache.fineract.infrastructure.core.exception.GeneralPlatformDomainRuleException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * {@link HtmlToPdfConverter} backed by a Gotenberg service (https://gotenberg.dev), which drives headless Chromium.
 *
 * Chromium's print pipeline takes the page margins from the print request rather than from CSS, so the margins are sent
 * as form fields while {@code preferCssPageSize} lets the template's {@code @page { size: ... }} rule decide the paper
 * size and orientation.
 */
@Slf4j
@Service
@ConditionalOnProperty(value = "fineract.report.pdf.enabled", havingValue = "true")
public class GotenbergHtmlToPdfConverter implements HtmlToPdfConverter {

    private static final MediaType TEXT_HTML = MediaType.get("text/html; charset=utf-8");
    private static final String CONVERT_HTML_ROUTE = "/forms/chromium/convert/html";

    private final OkHttpClient client;
    private final String endpointUrl;

    public GotenbergHtmlToPdfConverter(final OkHttpClient okHttpClient, final FineractProperties fineractProperties) {
        final FineractProperties.FineractReportPdfProperties properties = fineractProperties.getReport().getPdf();
        final String baseUrl = properties.getUrl();
        if (StringUtils.isBlank(baseUrl)) {
            // fail at startup with something actionable rather than on the first report with a NullPointerException
            throw new IllegalStateException(
                    "fineract.report.pdf.url must point at the PDF rendering service when fineract.report.pdf.enabled is true");
        }
        this.endpointUrl = (baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl) + CONVERT_HTML_ROUTE;
        // rendering is far slower than the general purpose client defaults allow for, so widen the timeouts
        this.client = okHttpClient.newBuilder() //
                .connectTimeout(Duration.ofSeconds(properties.getConnectTimeoutSeconds())) //
                .readTimeout(Duration.ofSeconds(properties.getReadTimeoutSeconds())) //
                .writeTimeout(Duration.ofSeconds(properties.getReadTimeoutSeconds())) //
                .build();
    }

    @Override
    public byte[] convert(final String html, final String footerHtml, final String documentName) {
        final MultipartBody.Builder bodyBuilder = new MultipartBody.Builder().setType(MultipartBody.FORM) //
                .addFormDataPart("files", "index.html", RequestBody.create(html, TEXT_HTML)) //
                .addFormDataPart("preferCssPageSize", "true") //
                .addFormDataPart("printBackground", "true") //
                .addFormDataPart("marginTop", "0.35") //
                .addFormDataPart("marginBottom", "0.45") //
                .addFormDataPart("marginLeft", "0.25") //
                .addFormDataPart("marginRight", "0.25");

        if (StringUtils.isNotBlank(footerHtml)) {
            bodyBuilder.addFormDataPart("files", "footer.html", RequestBody.create(footerHtml, TEXT_HTML));
        }

        final Request request = new Request.Builder().url(endpointUrl).post(bodyBuilder.build()).build();

        try (Response response = client.newCall(request).execute()) {
            final ResponseBody responseBody = response.body();
            if (!response.isSuccessful()) {
                final String detail = responseBody == null ? "" : StringUtils.abbreviate(responseBody.string(), 500);
                log.error("PDF rendering of report {} failed with HTTP {}: {}", documentName, response.code(), detail);
                throw new GeneralPlatformDomainRuleException("error.msg.report.pdf.render.failed",
                        "Rendering report %s to PDF failed with HTTP status %d".formatted(documentName, response.code()), documentName,
                        response.code());
            }
            if (responseBody == null) {
                throw new GeneralPlatformDomainRuleException("error.msg.report.pdf.render.failed",
                        "Rendering report %s to PDF returned an empty response".formatted(documentName), documentName);
            }
            return responseBody.bytes();
        } catch (final IOException e) {
            // AbstractPlatformException picks a Throwable out of the message arguments and keeps it as the cause
            throw new GeneralPlatformDomainRuleException("error.msg.report.pdf.renderer.unreachable",
                    "The PDF rendering service at %s could not be reached".formatted(endpointUrl), endpointUrl, e);
        }
    }
}
