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
-- Aging Summary (Arrears in Months) — stretchy_report id 61, PostgreSQL.
--
-- Parameters:
--   id 5   OfficeIdSelectOne    -> ${officeId}
--   ${currentUserHierarchy} is injected by the server.
--   No currency parameter: every KC24 loan is in Cambodian riel, so the core report's
--   Currency parameter and column are dropped (aging-summary-months-deploy.sql unlinks it).
--
-- What was wrong with the core version:
--   * It was a copy of the day-bucket aging (header "Days In Arrears", buckets
--     0-30 / 30-60 / 60-90 / 90-180 / 180-360 / > 360), so despite its name it never
--     showed months. It now buckets by months in arrears, mirroring the Weeks report:
--     On Schedule, 1 .. 12, 12+ ("Up To": 1 day .. 1 month overdue = 1, 1 month + 1 day = 2).
--   * It listed every bucket even with no loans in it. Only buckets that hold loans are shown.
--   * A loan with an m_loan_arrears_aging row is in arrears; one whose overdue date is
--     the business date itself was reported "On Schedule". It now lands in bucket 1.
-- =====================================================================================
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
ORDER BY l.months_in_arrears
