# Templated PDF Report Export

Stretchy reports can be exported as a PDF whose layout is defined by an HTML template, rather than
as the unstyled grid produced by the pre-existing `exportPDF` target.

Claude artifact: https://claude.ai/code/artifact/832b978b-23b5-4217-af17-d1daaa5cb2b7

The two exports are independent and both remain available:

| Query parameter | Renderer | Layout |
| --- | --- | --- |
| `exportPDF=true` | OpenPDF, in process | A single table drawn onto a `PageSize.B0` page. No title, no page furniture, no styling. |
| `exportPdfTemplate=true` | Headless Chromium, out of process | Whatever the HTML template defines: paper size, title block, repeating column headings, page numbers, per-column alignment. |

## Why an external renderer

The driving requirement is complex script support. Khmer, like the Indic scripts, needs OpenType
`GSUB` glyph substitution to compose subscript consonants (coeng) and reorder vowel signs. OpenPDF —
and therefore also JasperReports and the retired Pentaho integration, which both export through it —
performs no shaping: Khmer renders as unshaped or missing glyphs regardless of which font is embedded.

Chromium shapes text through HarfBuzz, so delegating the final render to a browser engine is what
makes non-Latin reports viable. HTML and CSS then come along as the layout language, with the
secondary benefit that a template can be authored and previewed in any browser.

## Enabling the feature

The export is disabled by default. Both the export target and the renderer client are annotated
`@ConditionalOnProperty`, so when the feature is off neither bean is created, `PDF_TEMPLATE` is
absent from `/runreports/availableExports/{reportName}`, and a request for it fails with the
standard `error.msg.report.export.mode.unavailable`.

| Property | Environment variable | Default |
| --- | --- | --- |
| `fineract.report.pdf.enabled` | `FINERACT_REPORT_PDF_ENABLED` | `false` |
| `fineract.report.pdf.url` | `FINERACT_REPORT_PDF_URL` | `http://localhost:3000` |
| `fineract.report.pdf.connect-timeout-seconds` | `FINERACT_REPORT_PDF_CONNECT_TIMEOUT_SECONDS` | `10` |
| `fineract.report.pdf.read-timeout-seconds` | `FINERACT_REPORT_PDF_READ_TIMEOUT_SECONDS` | `120` |

`fineract.report.pdf.url` is the base URL of the rendering service; the Gotenberg route is appended
by the client. When the feature is enabled and the URL is blank the application fails to start with
an explicit message, rather than deferring the failure to the first report run.

With Docker everything is already wired:

```bash
docker compose -f docker-compose-postgresql.yml up -d --build
```

Running the server directly:

```bash
docker build -t fineract-gotenberg config/docker/gotenberg
docker run -d -p 3000:3000 --name gotenberg fineract-gotenberg

export FINERACT_REPORT_PDF_ENABLED=true
export FINERACT_REPORT_PDF_URL=http://localhost:3000
./gradlew devRun
```

Confirm the export registered — `PDF_TEMPLATE` must appear in the response:

```bash
curl --insecure -u mifos:password -H "Fineract-Platform-TenantId: default" \
  "https://localhost:8443/fineract-provider/api/v1/runreports/availableExports/Client%20Listing"
```

## Running a report

```
GET /runreports/{reportName}?exportPdfTemplate=true&R_officeId=1&R_startDate=2026-01-01
```

```bash
curl --insecure -u mifos:password -H "Fineract-Platform-TenantId: default" \
  "https://localhost:8443/fineract-provider/api/v1/runreports/EOD%20Collection%20By%20Loan%20Officer?exportPdfTemplate=true&R_officeId=1&R_startDate=2026-01-01&R_endDate=2026-01-31" \
  -o eod.pdf
```

The response is `application/pdf` with a `Content-Disposition` filename built the same way as the
CSV export.

Everything else about report execution is unchanged. The report must exist in `stretchy_report`, the
caller needs the corresponding `READ_<report name>` entry in `m_permission`, and `R_` parameters are
still validated against `stretchy_parameter`.

No template needs to be installed. With none present the report is rendered with the built-in layout
at `fineract-provider/src/main/resources/templates/report/default-report.mustache`: A4 landscape,
column headings repeated on every page, alternating row shading, numeric columns right aligned, and
a footer carrying the report name and page numbers.

## Templates

### Storage and lookup

A per-report layout is a row in `m_template` whose `name` equals the report name exactly. The
exporter looks the row up by name and falls back to the built-in template when none exists. Because
the template is read from the database on every run, changing a layout requires neither a restart
nor a rebuild.

```sql
INSERT INTO m_template (name, entity, type, text)
VALUES ('EOD Collection By Loan Officer', NULL, NULL, $template$
<html>...</html>
$template$);
```

Wrap an HTML file rather than pasting it by hand:

```bash
{ printf "INSERT INTO m_template (name, entity, type, text) VALUES\n";
  printf "('EOD Collection By Loan Officer', NULL, NULL, \$template\$\n";
  cat /tmp/eod-collection.html;
  printf "\n\$template\$);\n"; } > /tmp/install-template.sql

docker compose -f docker-compose-postgresql.yml exec -T db \
  psql -U root -d fineract_default < /tmp/install-template.sql
```

`entity` and `type` are left `NULL` deliberately. A report layout is neither a client nor a loan
document, and `NULL` keeps the row out of the template pickers that `findByEntityAndType` drives in
the web application.

> **The `/v1/templates` REST API cannot create these rows.**
> `TemplateDomainServiceImpl.createTemplate` resolves the enums with
> `TemplateType.values()[request.getType()]`, which throws on a null `type`. Supplying
> `entity=0, type=0` to work around this mislabels the row as a client document template and
> surfaces it in the web application's client screens. Install report templates with SQL.

A worked example, with the scope documented inline, is at `sample/report-pdf-template-example.sql`.

To revert a report to the built-in layout, delete its row.

### Template scope

Templates are Mustache. The scope is built from the report's `GenericResultsetData`:

| Expression | Value |
| --- | --- |
| `{{reportName}}` | The report name |
| `{{generatedAt}}` | Tenant local timestamp, `yyyy-MM-dd HH:mm` |
| `{{rowCount}}`, `{{columnCount}}` | Size of the resultset |
| `{{#hasRows}}` / `{{^hasRows}}` | Sections for the populated and empty cases |
| `{{#params}}{{name}} {{value}}{{/params}}` | Every `R_` parameter the report was run with, `${...}` stripped from the key |
| `{{paramValues.<name>}}` | A single parameter looked up by name |
| `{{#columns}}{{name}}{{/columns}}` | Column headings. `{{#numeric}}` is set for integer and decimal columns |
| `{{#rows}}{{#cells}}{{value}}{{/cells}}{{/rows}}` | The data. `{{rowNumber}}` and `{{#odd}}` are available on each row |

Values are interpolated with `{{ }}`, which Mustache HTML-escapes, so report data cannot inject
markup into the rendered document.

### Authoring notes

- Set the paper with `@page { size: A4 landscape; }`. The client sends `preferCssPageSize`, so this
  rule decides size and orientation.
- Chromium takes page margins from the print request rather than from CSS. Use `padding` for spacing
  inside the page.
- `thead { display: table-header-group; }` is what repeats column headings across pages.
- The font stack must name a font installed in the renderer image.
- A template is ordinary HTML, so it can be opened directly in Chrome and checked through the
  browser's own print preview, which uses the same engine as the renderer.

## Renderer deployment

The rendering service is [Gotenberg](https://gotenberg.dev), which wraps headless Chromium behind an
HTTP conversion API.

`config/docker/gotenberg/Dockerfile` extends the upstream image with the fonts required for Khmer,
which the stock image does not carry:

```dockerfile
FROM gotenberg/gotenberg:8
USER root
RUN apt-get update \
    && apt-get install -y --no-install-recommends fonts-khmeros fonts-noto-core \
    && rm -rf /var/lib/apt/lists/*
USER gotenberg
```

This provides the Noto Sans/Serif Khmer and Khmer OS families. Fonts not packaged for Debian can be
added by copying the files into `/usr/share/fonts/truetype/`. To check what a running container has:

```bash
docker compose -f docker-compose-postgresql.yml exec gotenberg fc-list | grep -i khmer
```

The service is defined in `config/docker/compose/gotenberg.yml` and wired into
`docker-compose-postgresql.yml`, where Fineract declares a `service_healthy` dependency on it.
Settings for the container come from `config/docker/env/fineract-pdf.env`.

> **Gotenberg performs no authentication** and converts any document posted to it. The compose
> definition uses `expose` rather than `ports` so it stays reachable only from the internal network.
> Do not publish it.

## Report mailing jobs

The scheduled report mailing job accepts this export. `ExecuteReportMailingJobsTasklet` previously
required the response entity to be a `ByteArrayOutputStream`, a shape only the retired Pentaho path
produced; it now also accepts `byte[]`, which is what the templated PDF export returns and what
JAX-RS writes over REST. A mailing job with the `PDF` attachment format on a Table-type report
therefore now produces an attachment instead of logging an error.

## Implementation

| Type | Responsibility |
| --- | --- |
| `DatatableExportTargetParameter.PDF_TEMPLATE` | Binds the `exportPdfTemplate` query parameter |
| `TemplatePdfDatatableReportExportService` | The export target; retrieves the resultset, renders, converts, returns the response |
| `ReportHtmlRenderService` | Builds the template scope and compiles the Mustache into HTML and a page footer |
| `HtmlToPdfConverter` | Narrow interface over the HTML to PDF step, so the renderer can be replaced |
| `GotenbergHtmlToPdfConverter` | The Gotenberg implementation; posts a multipart request over the shared `OkHttpClient` |

Java sources live under
`fineract-provider/src/main/java/org/apache/fineract/infrastructure/dataqueries/service/export/`.

Because `DatatableReportingProcessService` injects every `DatatableReportExportService` and derives
its advertised targets from those beans, no registration step is required beyond the conditional
bean itself.

## Troubleshooting

| What you see | Why | Fix |
| --- | --- | --- |
| `Export mode PDF_TEMPLATE unavailable` | Feature flag off, so the bean was never created | Set `FINERACT_REPORT_PDF_ENABLED=true` and restart |
| Startup fails: *fineract.report.pdf.url must point at the PDF rendering service* | Enabled but no URL configured | Set `FINERACT_REPORT_PDF_URL` |
| `error.msg.report.pdf.renderer.unreachable` | Renderer down, or wrong URL for the network | From compose use `http://gotenberg:3000`, not `localhost` |
| `error.msg.report.pdf.render.failed` | Chromium rejected the HTML | The server log carries Gotenberg's own message; usually malformed markup in a hand-edited template |
| Empty boxes where Khmer should be | CSS names a font the container lacks | Check with `fc-list` as above |
| Fineract never becomes healthy | It waits on the renderer's health check | `docker compose -f docker-compose-postgresql.yml logs gotenberg` |
| `unknown report parameter X is not registered` | Pre-existing rule: once a report has any registered parameter, all must be registered | Add the row to `stretchy_parameter` |
| `Not authorised to run report` | Missing `READ_<report name>` permission | Insert the `m_permission` row |

## Limitations

- Rendering depends on an external service. If it is unreachable the export fails with
  `error.msg.report.pdf.renderer.unreachable`; other export targets are unaffected.
- Only reports of type `Table`, `Chart` and `SMS` are routed through
  `DatatableReportingProcessService` and can use this export.
- The template is resolved by exact name match; there is no inheritance or shared partial between
  templates.
