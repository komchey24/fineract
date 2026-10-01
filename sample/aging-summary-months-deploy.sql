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
-- One-shot deploy of the fixed "Aging Summary (Arrears in Months)" report.
--   1. Unlinks the Currency parameter from this report (every KC24 loan is in riel). The
--      shared currencyIdSelectAll parameter itself is left alone for other reports.
--   2. Replaces the report SQL. The query body is identical to
--      sample/aging-summary-months.sql (see that file for what changed and why).
--
-- Run against the TENANT database (fineract_default), not the tenant store.
-- Safe to re-run; one statement, so it aborts without changing anything if a row is missing.
-- No restart needed: the report SQL is read from the database per request.
-- =====================================================================================

DO $deploy$
DECLARE
    v_report_name text := 'Aging Summary (Arrears in Months)';

    v_report_sql text := $rpt$
WITH bizdate AS (
    -- the report runner rewrites CURRENT_DATE to the tenant's business date
    SELECT CURRENT_DATE AS d
),
loans AS (
    SELECT ml.id AS loanid,
           ml.principal_disbursed_derived AS principal,
           ml.interest_charged_derived AS interest,
           ml.principal_repaid_derived AS prinpaid,
           ml.interest_repaid_derived AS intpaid,
           laa.principal_overdue_derived AS prinoverdue,
           laa.interest_overdue_derived AS intoverdue,
           CASE
               WHEN laa.loan_id IS NULL OR laa.overdue_since_date_derived IS NULL
                    OR laa.overdue_since_date_derived > b.d THEN 0
               ELSE LEAST(13, GREATEST(1,
                   (EXTRACT(YEAR FROM age(b.d, laa.overdue_since_date_derived)) * 12
                    + EXTRACT(MONTH FROM age(b.d, laa.overdue_since_date_derived))
                    + CASE WHEN EXTRACT(DAY FROM age(b.d, laa.overdue_since_date_derived)) > 0 THEN 1 ELSE 0 END)::int))
           END AS months_in_arrears /* 0 = on schedule, 13 = more than 12 */
    FROM m_office mo
    JOIN m_office ounder ON ounder.hierarchy LIKE concat(mo.hierarchy, '%')
                        AND ounder.hierarchy LIKE concat('${currentUserHierarchy}', '%')
    JOIN m_client mc ON mc.office_id = ounder.id
    JOIN m_loan ml ON ml.client_id = mc.id
    CROSS JOIN bizdate b
    LEFT JOIN m_loan_arrears_aging laa ON laa.loan_id = ml.id
    WHERE ml.loan_status_id = 300 /* active */
      AND mo.id = '${officeId}'
)
SELECT CASE
           WHEN l.months_in_arrears = 0 THEN 'On Schedule'
           WHEN l.months_in_arrears = 13 THEN '12+'
           ELSE l.months_in_arrears::text
       END AS "Months In Arrears (Up To)",
       count(l.loanid) AS "No Of Loans",
       coalesce(sum(l.principal), 0) AS "Original Principal",
       coalesce(sum(l.interest), 0) AS "Original Interest",
       coalesce(sum(l.prinpaid), 0) AS "Principal Paid",
       coalesce(sum(l.intpaid), 0) AS "Interest Paid",
       coalesce(sum(l.prinoverdue), 0) AS "Principal Overdue",
       coalesce(sum(l.intoverdue), 0) AS "Interest Overdue"
FROM loans l
GROUP BY l.months_in_arrears
ORDER BY l.months_in_arrears$rpt$;
BEGIN
    DELETE FROM stretchy_report_parameter
    WHERE report_id = (SELECT id FROM stretchy_report WHERE report_name = v_report_name)
      AND parameter_id = (SELECT id FROM stretchy_parameter WHERE parameter_name = 'currencyIdSelectAll');
    RAISE NOTICE 'Currency parameter unlinked from "%".', v_report_name;

    UPDATE stretchy_report SET report_sql = v_report_sql WHERE report_name = v_report_name;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'No stretchy_report named "%" — nothing was changed.', v_report_name;
    END IF;
    RAISE NOTICE 'Report SQL updated for "%".', v_report_name;
END
$deploy$;
