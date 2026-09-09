--
-- Licensed to the Apache Software Foundation (ASF) under one
-- or more contributor license agreements. See the NOTICE file
-- distributed with this work for additional information
-- regarding copyright ownership. The ASF licenses this file
-- to you under the Apache License, Version 2.0 (the
-- "License"); you may not use this file except in compliance
-- with the License. You may obtain a copy of the License at
--
-- http://www.apache.org/licenses/LICENSE-2.0
--
-- Unless required by applicable law or agreed to in writing,
-- software distributed under the License is distributed on an
-- "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
-- KIND, either express or implied. See the License for the
-- specific language governing permissions and limitations
-- under the License.
--

-- Optional per-report layout for the templated PDF export (exportPdfTemplate=true).
--
-- The exporter looks for an m_template row whose name is exactly the stretchy report name; when none exists it
-- falls back to the built-in generic table layout shipped at
-- fineract-provider/src/main/resources/templates/report/default-report.mustache
--
-- entity and type are left NULL on purpose: a report layout is neither a client nor a loan document, and leaving
-- them NULL keeps the row out of the client/loan template pickers in the web app.
--
-- Scope available to the template:
--   {{reportName}}                  the report name
--   {{generatedAt}}                 tenant local timestamp, yyyy-MM-dd HH:mm
--   {{rowCount}} {{columnCount}}    sizes of the resultset
--   {{#hasRows}}...{{/hasRows}}     rendered only when the report returned rows
--   {{#params}}{{name}} {{value}}   the R_ parameters the report was run with
--   {{paramValues.startDate}}       a single parameter looked up by name
--   {{#columns}}{{name}}            column headings; {{#numeric}} is set for integer and decimal columns
--   {{#rows}}{{#cells}}{{value}}    the data; {{rowNumber}} and {{#odd}} are available per row
--
-- Values are interpolated with {{ }}, which Mustache HTML-escapes, so report data cannot inject markup.
--
-- Chromium takes the page margins from the render request, so set the paper with @page { size: ... } and do the
-- rest of the spacing with padding. The font stack must name a font installed in the renderer image; the image
-- built from config/docker/gotenberg/Dockerfile provides Noto Sans Khmer and the Khmer OS family.

INSERT INTO m_template (name, entity, type, text) VALUES ('EOD Collection By Loan Officer', NULL, NULL, $template$
<html>
<head>
<meta charset="utf-8"/>
<style>
  @page { size: A4 landscape; }
  body { font-family: "Noto Sans Khmer", "Khmer OS Battambang", sans-serif; font-size: 8pt; margin: 0; }
  .title { font-size: 14pt; font-weight: 600; text-align: center; }
  .subtitle { text-align: center; color: #555; margin-bottom: 10px; }
  table { width: 100%; border-collapse: collapse; }
  thead { display: table-header-group; }
  th, td { border: 0.5pt solid #999; padding: 3px 5px; }
  th { background: #e8eaed; }
  td.num, th.num { text-align: right; white-space: nowrap; }
  tbody tr:last-child { font-weight: 600; background: #f0f0f0; }
</style>
</head>
<body>
  <div class="title">{{reportName}}</div>
  <div class="subtitle">{{paramValues.startDate}} — {{paramValues.endDate}} · {{generatedAt}}</div>
  {{#hasRows}}
  <table>
    <thead><tr>{{#columns}}<th class="{{#numeric}}num{{/numeric}}">{{name}}</th>{{/columns}}</tr></thead>
    <tbody>{{#rows}}<tr>{{#cells}}<td class="{{#numeric}}num{{/numeric}}">{{value}}</td>{{/cells}}</tr>{{/rows}}</tbody>
  </table>
  {{/hasRows}}
  {{^hasRows}}<p style="text-align:center">គ្មានទិន្នន័យ</p>{{/hasRows}}
</body>
</html>
$template$);
