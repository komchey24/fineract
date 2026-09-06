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
-- One-shot production deploy for the expect-repayment PDF header.
--
-- Applies BOTH database halves of the change:
--   1. the report SQL, gaining a 20th column that carries the loan officer, and
--   2. the per-report PDF layout that puts the date, the loan officer and the total
--      outstanding (principal + interest) in the sheet header.
--
-- The remaining half — the quirks-mode doctype fix in default-report.mustache — ships
-- inside the JAR and needs no SQL.
--
-- BEFORE RUNNING: set v_report_name below to the report's real name. Find it with
--
--   SELECT id, report_name FROM stretchy_report WHERE report_sql LIKE '%next_duedate%';
--
-- Run against the TENANT database (fineract_default), not the tenant store.
--
-- Safe to re-run: the report SQL is replaced in place, and the layout is replaced if it
-- already exists rather than colliding with m_template's unique name constraint. The whole
-- thing is one statement, so it either fully applies or changes nothing. It aborts without
-- touching anything if the report name does not match a row.
--
-- No restart is needed for these two: the exporter reads both from the database per request.
-- =====================================================================================

DO $deploy$
DECLARE
    v_report_name text := 'Expected Repayment';   -- <<< SET THIS

    v_report_sql text := $rpt$WITH sched AS (
    SELECT r.loan_id,
        SUM(CASE WHEN r.completed_derived THEN 1 ELSE 0 END) AS paid_installments,
        SUM(CASE WHEN NOT r.completed_derived THEN 1 ELSE 0 END) AS remaining_installments,
        SUM(CASE WHEN NOT r.completed_derived
                 THEN COALESCE(r.principal_amount,0)
                    - COALESCE(r.principal_completed_derived,0)
                    - COALESCE(r.principal_writtenoff_derived,0)
                 ELSE 0 END) AS principal_outstanding,
        SUM(CASE WHEN NOT r.completed_derived
                 THEN COALESCE(r.interest_amount,0)
                    - COALESCE(r.interest_completed_derived,0)
                    - COALESCE(r.interest_waived_derived,0)
                    - COALESCE(r.interest_writtenoff_derived,0)
                 ELSE 0 END) AS interest_outstanding,
        SUM(CASE WHEN NOT r.completed_derived AND r.duedate <= ${endDate}::date
                 THEN COALESCE(r.principal_amount,0)
                    - COALESCE(r.principal_completed_derived,0)
                    - COALESCE(r.principal_writtenoff_derived,0)
                 ELSE 0 END) AS principal_due,
        SUM(CASE WHEN NOT r.completed_derived AND r.duedate <= ${endDate}::date
                 THEN COALESCE(r.interest_amount,0)
                    - COALESCE(r.interest_completed_derived,0)
                    - COALESCE(r.interest_waived_derived,0)
                    - COALESCE(r.interest_writtenoff_derived,0)
                 ELSE 0 END) AS interest_due,
        MIN(CASE WHEN NOT r.completed_derived THEN r.duedate END) AS next_duedate
    FROM m_loan_repayment_schedule r
    WHERE r.installment > 0
    GROUP BY r.loan_id
),
inst AS (
    SELECT DISTINCT ON (r.loan_id) r.loan_id,
           COALESCE(r.principal_amount,0) + COALESCE(r.interest_amount,0)
         + COALESCE(r.fee_charges_amount,0) AS installment_amount
    FROM m_loan_repayment_schedule r
    WHERE r.installment > 0
    ORDER BY r.loan_id, r.installment
)
SELECT
    -- 1  No
    ROW_NUMBER() OVER (ORDER BY
        CASE WHEN mlaa.overdue_since_date_derived IS NULL THEN 0 ELSE 1 END,
        ms.display_name,
        mlaa.overdue_since_date_derived,
        ml.disbursedon_date)                          AS "ល.រ",
    -- 2  Date (disbursement date)
    ml.disbursedon_date                               AS "កាលបរិច្ឆេទ",
    -- 3  Loan Acc#
    LTRIM(ml.account_no,'0')                          AS "កិច្ចសន្យា",
    -- 4  Client Acc#
    LTRIM(mc.account_no,'0')                          AS "កូដ",
    -- 5  Client name
    mc.display_name                                   AS "ឈ្មោះអតិថិជន",
    -- 6  Mobile
    COALESCE(mc.mobile_no,'-')                        AS "ទំនាក់ទំនង",
    -- 7  Address
    COALESCE((SELECT a.town_village
              FROM m_client_address ca
              JOIN m_address a ON a.id = ca.address_id
              WHERE ca.client_id = mc.id AND ca.is_active = true
              ORDER BY ca.id LIMIT 1), '-')           AS "អាសយដ្ឋាន",
    -- 8  Amount (principal)
    ml.principal_amount                               AS "ទឹកប្រាក់ខ្ចី",
    -- 9  Term
    ml.number_of_repayments                           AS "រយៈពេល",
    -- 10 Due amount (installment)
    inst.installment_amount                           AS "ទឹកប្រាក់ត្រូវបង់",
    -- 11 Type
    CASE ml.repayment_period_frequency_enum
         WHEN 0 THEN 'ថ្ងៃ' WHEN 1 THEN 'សប្តាហ៍'
         WHEN 2 THEN 'ខែ'  WHEN 3 THEN 'ឆ្នាំ' END    AS "ប្រភេទកម្ចី",
    -- 12 Late (days)
    COALESCE(${endDate}::date - mlaa.overdue_since_date_derived, 0) AS "យឺត",
    -- 13 Paid installments
    sched.paid_installments                           AS "បង់រួច",
    -- 14 Unpaid installments
    sched.remaining_installments                      AS "នៅសល់",
    -- 15 Total principal due
    sched.principal_outstanding                       AS "សរុបដើម",
    -- 16 Principal due (as of endDate)
    sched.principal_due                               AS "ប្រាក់ដើម",
    -- 17 Total interest due
    sched.interest_outstanding                        AS "សរុបការ",
    -- 18 Interest due (as of endDate)
    sched.interest_due                                AS "ការប្រាក់",
    -- 19 Total due (as of endDate)
    sched.principal_due + sched.interest_due          AS "សរុប",
    -- 20 Loan officer — header only. The PDF layout in sample/expect-repayment-template.sql lifts this column
    --    into the sheet header and removes it from the table, so it costs no width on the printed page.
    COALESCE(ms.display_name,'-')                     AS "មន្ត្រីឥណទាន"
FROM m_office mo
JOIN m_office ounder
     ON ounder.hierarchy LIKE CONCAT(mo.hierarchy, '%')
    AND ounder.hierarchy LIKE CONCAT('${currentUserHierarchy}', '%')
JOIN m_client mc ON mc.office_id = ounder.id
JOIN m_loan ml   ON ml.client_id = mc.id AND ml.loan_status_id = 300
JOIN sched       ON sched.loan_id = ml.id
JOIN inst        ON inst.loan_id  = ml.id
LEFT JOIN m_loan_arrears_aging mlaa ON mlaa.loan_id = ml.id
LEFT JOIN m_staff ms ON ms.id = ml.loan_officer_id
WHERE mo.id = '${officeId}'
  AND (COALESCE(ml.loan_officer_id, -10) = ${loanOfficerId} OR ${loanOfficerId} = -1)
  AND ml.disbursedon_date >= ${startDate}::date - INTERVAL '10 years'
  AND sched.next_duedate <= ${endDate}::date
ORDER BY
    CASE WHEN mlaa.overdue_since_date_derived IS NULL THEN 0 ELSE 1 END,
    ms.display_name,
    mlaa.overdue_since_date_derived,
    ml.disbursedon_date
$rpt$;

    v_template text := $template$<!DOCTYPE html>
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
$template$;
BEGIN
    UPDATE stretchy_report SET report_sql = v_report_sql WHERE report_name = v_report_name;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'No stretchy_report named "%" — fix v_report_name and re-run. Nothing was changed.',
            v_report_name;
    END IF;
    RAISE NOTICE 'Report SQL updated for "%".', v_report_name;

    UPDATE m_template SET text = v_template WHERE name = v_report_name;
    IF FOUND THEN
        RAISE NOTICE 'Existing PDF layout replaced for "%".', v_report_name;
    ELSE
        INSERT INTO m_template (name, entity, type, text) VALUES (v_report_name, NULL, NULL, v_template);
        RAISE NOTICE 'PDF layout installed for "%".', v_report_name;
    END IF;
END
$deploy$;

-- Verification: one row, with the officer column present and a layout attached.
SELECT r.id,
       r.report_name,
       r.report_sql LIKE '%មន្ត្រីឥណទាន%' AS has_officer_column,
       t.id IS NOT NULL                                  AS has_pdf_layout,
       length(t.text)                                    AS layout_bytes
  FROM stretchy_report r
  LEFT JOIN m_template t ON t.name = r.report_name
 WHERE r.report_name = 'Expected Repayment';   -- <<< SET THIS TOO
