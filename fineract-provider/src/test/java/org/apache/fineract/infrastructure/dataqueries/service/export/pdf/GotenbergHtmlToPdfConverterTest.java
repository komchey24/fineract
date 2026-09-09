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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.OkHttpClient;
import org.apache.fineract.infrastructure.core.config.FineractProperties;
import org.apache.fineract.infrastructure.core.exception.GeneralPlatformDomainRuleException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Exercises the Gotenberg client against a throwaway local HTTP server, so the multipart request shape is verified
 * without needing the renderer container.
 */
class GotenbergHtmlToPdfConverterTest {

    private static final byte[] PDF_BYTES = "%PDF-1.4 fake".getBytes(StandardCharsets.UTF_8);

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private String startServer(int status, byte[] responseBody, AtomicReference<String> capturedRequest) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            if (capturedRequest != null) {
                capturedRequest.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            }
            exchange.sendResponseHeaders(status, responseBody.length);
            exchange.getResponseBody().write(responseBody);
            exchange.close();
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private GotenbergHtmlToPdfConverter converter(String baseUrl) {
        FineractProperties.FineractReportPdfProperties pdf = new FineractProperties.FineractReportPdfProperties();
        pdf.setEnabled(true);
        pdf.setUrl(baseUrl);
        pdf.setConnectTimeoutSeconds(5);
        pdf.setReadTimeoutSeconds(10);

        FineractProperties.FineractReportProperties report = new FineractProperties.FineractReportProperties();
        report.setPdf(pdf);

        FineractProperties properties = new FineractProperties();
        properties.setReport(report);

        return new GotenbergHtmlToPdfConverter(new OkHttpClient(), properties);
    }

    @Test
    void postsTheDocumentAsMultipartAndReturnsThePdfBytes() throws IOException {
        AtomicReference<String> captured = new AtomicReference<>();
        String baseUrl = startServer(200, PDF_BYTES, captured);

        byte[] pdf = converter(baseUrl).convert("<html>ថ្ងៃ</html>", "<html>footer</html>", "EOD Collection");

        assertArrayEquals(PDF_BYTES, pdf);
        String request = captured.get();
        assertTrue(request.contains("filename=\"index.html\""), "body document part missing");
        assertTrue(request.contains("filename=\"footer.html\""), "footer part missing");
        assertTrue(request.contains("<html>ថ្ងៃ</html>"), "Khmer content did not survive the request encoding");
        // the template owns the paper size through @page, so the renderer must be told to honour it
        assertTrue(request.contains("preferCssPageSize"), "preferCssPageSize field missing");
        assertTrue(request.contains("printBackground"), "printBackground field missing");
    }

    @Test
    void omitsTheFooterPartWhenNoFooterIsGiven() throws IOException {
        AtomicReference<String> captured = new AtomicReference<>();
        String baseUrl = startServer(200, PDF_BYTES, captured);

        converter(baseUrl).convert("<html>body</html>", null, "EOD Collection");

        assertTrue(captured.get().contains("filename=\"index.html\""), "body document part missing");
        assertTrue(!captured.get().contains("filename=\"footer.html\""), "footer part should be absent");
    }

    @Test
    void trailingSlashOnTheConfiguredUrlIsTolerated() throws IOException {
        AtomicReference<String> captured = new AtomicReference<>();
        String baseUrl = startServer(200, PDF_BYTES, captured);

        byte[] pdf = converter(baseUrl + "/").convert("<html>body</html>", null, "EOD Collection");

        assertArrayEquals(PDF_BYTES, pdf);
    }

    @Test
    void aMissingUrlFailsFastRatherThanOnTheFirstReport() {
        FineractProperties.FineractReportPdfProperties pdf = new FineractProperties.FineractReportPdfProperties();
        pdf.setEnabled(true);
        FineractProperties.FineractReportProperties report = new FineractProperties.FineractReportProperties();
        report.setPdf(pdf);
        FineractProperties properties = new FineractProperties();
        properties.setReport(report);

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> new GotenbergHtmlToPdfConverter(new OkHttpClient(), properties));
        assertTrue(exception.getMessage().contains("fineract.report.pdf.url"));
    }

    @Test
    void rendererErrorIsReportedAsADomainRuleException() throws IOException {
        String baseUrl = startServer(400, "malformed html".getBytes(StandardCharsets.UTF_8), null);
        GotenbergHtmlToPdfConverter converter = converter(baseUrl);

        GeneralPlatformDomainRuleException exception = assertThrows(GeneralPlatformDomainRuleException.class,
                () -> converter.convert("<html>body</html>", null, "EOD Collection"));

        assertEquals("error.msg.report.pdf.render.failed", exception.getGlobalisationMessageCode());
    }

    @Test
    void unreachableRendererIsReportedAsADomainRuleExceptionKeepingTheCause() {
        // port 1 is reserved and never listening
        GotenbergHtmlToPdfConverter converter = converter("http://127.0.0.1:1");

        GeneralPlatformDomainRuleException exception = assertThrows(GeneralPlatformDomainRuleException.class,
                () -> converter.convert("<html>body</html>", null, "EOD Collection"));

        assertEquals("error.msg.report.pdf.renderer.unreachable", exception.getGlobalisationMessageCode());
        assertTrue(exception.getCause() instanceof IOException, "the underlying I/O failure was lost");
    }
}
