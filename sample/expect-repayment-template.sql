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

-- =====================================================================================
-- Per-report PDF layout for the collection / expected-repayment report, reproducing the
-- header of sample/expect-repayment.pdf:
--
--   * the report title,
--   * the loan officer the sheet belongs to,
--   * the period it covers,
--   * the total amount still outstanding — principal plus interest.
--
-- sample/expect-repayment.html is the same markup with sample data pasted in; open that
-- file in a browser to preview a change before deploying it here.
--
-- PREREQUISITE: the report SQL must expose the loan officer as its last column, named
-- exactly "មន្ត្រីឥណទាន". sample/expect-repayment.sql already does; the layout below
-- lifts that column into the header and drops it from the table.
--
-- The exporter picks this row up by matching m_template.name against the stretchy report
-- name, so REPLACE 'Expected Repayment' below with the report's actual name:
--
--   SELECT id, report_name FROM stretchy_report WHERE report_sql LIKE '%next_duedate%';
--
-- entity and type stay NULL on purpose — a report layout is neither a client nor a loan
-- document, and NULL keeps the row out of the web app's document template pickers.
-- =====================================================================================

INSERT INTO m_template (name, entity, type, text) VALUES ('Expected Repayment', NULL, NULL, $template$
<!DOCTYPE html>
<!-- The doctype is load-bearing: without it Chromium renders in quirks mode, where a table does not
     inherit font-size from body and falls back to 16px, and every column overflows. -->
<html lang="km">
<head>
<meta charset="utf-8"/>
<title>{{reportName}}</title>
<style>
  /* preferCssPageSize=true is sent to Gotenberg, so this rule decides paper size and orientation.
     Margins come from the render request (0.35/0.45/0.25/0.25in), because Chromium ignores @page margins. */
  @page { size: A4 landscape; }

  /* Khmer needs a font carrying the script's OpenType shaping tables; the renderer image provides these two. */
  body {
    font-family: "Noto Sans Khmer", "Khmer OS Battambang", "Khmer OS", "Noto Sans", "DejaVu Sans", sans-serif;
    font-size: 7pt;
    color: #000;
    margin: 0;
  }

  /* ------------------------------------------------------------------ header */
  header { margin-bottom: 6px; }
  h1 {
    font-family: "Khmer OS Muol Light", "Khmer OS Muol", "Noto Serif Khmer", "Noto Sans Khmer", serif;
    font-size: 12pt;
    font-weight: 600;
    text-align: center;
    margin: 0 0 5px 0;
  }
  /* Loan officer on the left, the reporting period in the middle, the outstanding total on the right —
     the three facts the printed sheet is read by. */
  .head-line {
    display: flex;
    align-items: baseline;
    justify-content: space-between;
    gap: 12px;
    font-size: 9pt;
    padding-bottom: 4px;
  }
  .head-line .period { flex: 1 1 auto; text-align: center; }
  .head-line .officer, .head-line .outstanding { flex: 0 0 auto; white-space: nowrap; }
  .head-line .label { color: #333; }
  .head-line .value { font-weight: 600; }

  /* ------------------------------------------------------------------- table */
  table { width: 100%; border-collapse: collapse; table-layout: fixed; }
  thead { display: table-header-group; }   /* repeat the headings on every page */
  tr { break-inside: avoid; }
  th, td {
    border: 0.5pt solid #666;
    padding: 2px 3px;
    text-align: left;
    vertical-align: top;
    overflow-wrap: anywhere;
  }
  th { background: #e8eaed; font-weight: 600; font-size: 6.5pt; text-align: center; vertical-align: middle; }
  th.num, td.num { text-align: right; white-space: nowrap; }
  th.num { white-space: normal; text-align: center; }   /* only the figures stay on one line */
  td:nth-child(2) { white-space: nowrap; }   /* កាលបរិច្ឆេទ never wraps mid-date */
  /* Figures and dates carry no Khmer, and the Khmer faces set Latin digits far wider than the columns
     allow — the printed sheet gets its width budget from a condensed Latin face, as the original did
     with Arial Narrow. Every name here except Arial Narrow is present in the renderer image. */
  td.num, th.num, td:nth-child(2) {
    font-family: "DejaVu Sans Condensed", "Liberation Sans Narrow", "Arial Narrow", "Liberation Sans",
                 "DejaVu Sans", sans-serif;
  }
  .empty { padding: 16px; text-align: center; color: #777; font-style: italic; }

  /* Column widths, in the order the report SELECT lists them, taken from the vertical rules of
     sample/expect-repayment.pdf and rescaled over 19 columns — the PDF carries a 20th, blank, write-in
     column this report has no data for. The loan officer column is left unsized: the script removes it. */
  col:nth-child(1)   { width:  2.63%; }   /* ល.រ */
  col:nth-child(2)   { width:  5.85%; }   /* កាលបរិច្ឆេទ */
  col:nth-child(3)   { width:  4.81%; }   /* កិច្ចសន្យា */
  col:nth-child(4)   { width:  4.61%; }   /* កូដ */
  col:nth-child(5)   { width:  7.22%; }   /* ឈ្មោះអតិថិជន */
  col:nth-child(6)   { width: 10.49%; }   /* ទំនាក់ទំនង */
  col:nth-child(7)   { width:  9.19%; }   /* អាសយដ្ឋាន */
  col:nth-child(8)   { width:  5.11%; }   /* ទឹកប្រាក់ខ្ចី */
  col:nth-child(9)   { width:  3.69%; }   /* រយៈពេល */
  col:nth-child(10)  { width:  5.12%; }   /* ទឹកប្រាក់ត្រូវបង់ */
  col:nth-child(11)  { width:  5.93%; }   /* ប្រភេទកម្ចី */
  col:nth-child(12)  { width:  2.70%; }   /* យឺត */
  col:nth-child(13)  { width:  3.90%; }   /* បង់រួច */
  col:nth-child(14)  { width:  4.90%; }   /* នៅសល់ */
  col:nth-child(15)  { width:  5.01%; }   /* សរុបដើម */
  col:nth-child(16)  { width:  4.92%; }   /* ប្រាក់ដើម */
  col:nth-child(17)  { width:  4.61%; }   /* សរុបការ */
  col:nth-child(18)  { width:  4.62%; }   /* ការប្រាក់ */
  col:nth-child(19)  { width:  4.70%; }   /* សរុប */
</style>
</head>
<body>

<header>
  <h1>{{reportName}}</h1>
  <div class="head-line">
    <span class="officer"><span class="label">មន្ត្រីឥណទាន៖</span> <span class="value" id="officer">&mdash;</span></span>
    <span class="period">ចាប់ពីថ្ងៃទី <span class="value" id="start-date">{{paramValues.startDate}}</span>
      ដល់ថ្ងៃទី <span class="value" id="end-date">{{paramValues.endDate}}</span></span>
    <span class="outstanding"><span class="label">ទឹកប្រាក់នៅសល់សរុប៖</span> <span class="value" id="outstanding">&mdash;</span></span>
  </div>
</header>

{{#hasRows}}
<table id="report">
  <colgroup>{{#columns}}<col/>{{/columns}}</colgroup>
  <thead>
    <tr>{{#columns}}<th class="{{#numeric}}num{{/numeric}}">{{name}}</th>{{/columns}}</tr>
  </thead>
  <tbody>
    {{#rows}}
    <tr>{{#cells}}<td class="{{#numeric}}num{{/numeric}}">{{value}}</td>{{/cells}}</tr>
    {{/rows}}
  </tbody>
</table>
{{/hasRows}}
{{^hasRows}}
<div class="empty">គ្មានទិន្នន័យ</div>
{{/hasRows}}

<script>
/*
 * The report resultset reaches the template as one flat table of strings: no aggregates, no loan officer name
 * resolved from the id, and figures still carrying the scale Postgres returns them at. This script is what turns
 * that into the printed sheet, and Gotenberg's Chromium runs it before it takes the page snapshot.
 *
 *  1. formats every numeric cell with thousand separators and no decimals,
 *  2. rewrites ISO dates as dd/MM/yyyy, in the cells and in the header period,
 *  3. totals the two outstanding columns — principal (សរុបដើម) plus interest (សរុបការ) — into the header,
 *  4. lifts the loan officer column into the header and removes it from the table.
 *
 * Columns are found by heading text rather than position, so reordering the report SELECT does not break this.
 * Each header field is left showing an em dash when its column is missing, which makes a mismatch between this
 * layout and the report SQL visible on the printed sheet instead of silently producing a wrong total.
 */
(function () {
  'use strict';

  var PRINCIPAL_OUTSTANDING = 'សរុបដើម';
  var INTEREST_OUTSTANDING = 'សរុបការ';
  var LOAN_OFFICER = 'មន្ត្រីឥណទាន';

  var ISO_DATE = /^(\d{4})-(\d{2})-(\d{2})$/;

  function asDayFirst(text) {
    var parts = ISO_DATE.exec(text);
    return parts ? parts[3] + '/' + parts[2] + '/' + parts[1] : null;
  }

  ['start-date', 'end-date'].forEach(function (id) {
    var element = document.getElementById(id);
    if (!element) { return; }
    var dayFirst = asDayFirst(element.textContent.trim());
    if (dayFirst) { element.textContent = dayFirst; }
  });

  var table = document.getElementById('report');
  if (!table || !table.tHead || !table.tBodies.length) { return; }

  var headings = Array.prototype.map.call(table.tHead.rows[0].cells, function (cell) {
    return cell.textContent.trim();
  });
  var outstandingColumns = [PRINCIPAL_OUTSTANDING, INTEREST_OUTSTANDING]
    .map(function (name) { return headings.indexOf(name); })
    .filter(function (index) { return index >= 0; });
  var officerColumn = headings.indexOf(LOAN_OFFICER);

  var amount = new Intl.NumberFormat('en-US', { maximumFractionDigits: 0 });
  var total = 0;
  var officer = '';

  Array.prototype.forEach.call(table.tBodies[0].rows, function (row) {
    Array.prototype.forEach.call(row.cells, function (cell, column) {
      var text = cell.textContent.trim();

      if (column === officerColumn) {
        if (!officer) { officer = text; }
        return;
      }

      var dayFirst = asDayFirst(text);
      if (dayFirst) {
        cell.textContent = dayFirst;
        return;
      }

      if (!cell.classList.contains('num') || text === '') { return; }
      var value = Number(text);
      if (!isFinite(value)) { return; }

      cell.textContent = amount.format(value);
      if (outstandingColumns.indexOf(column) >= 0) { total += value; }
    });
  });

  // Only claim a total when both outstanding columns were actually found.
  if (outstandingColumns.length === 2) {
    document.getElementById('outstanding').textContent = amount.format(total);
  }
  if (officer) {
    document.getElementById('officer').textContent = officer;
  }

  // Drop the loan officer column now that its value sits in the header. The colgroup has to shrink with the
  // cells, or every width defined after that column would apply to the wrong one.
  if (officerColumn >= 0) {
    Array.prototype.forEach.call(table.rows, function (row) {
      if (row.cells[officerColumn]) { row.deleteCell(officerColumn); }
    });
    var columnDefinition = table.querySelectorAll('colgroup col')[officerColumn];
    if (columnDefinition) { columnDefinition.parentNode.removeChild(columnDefinition); }
  }
}());
</script>
</body>
</html>
$template$);
