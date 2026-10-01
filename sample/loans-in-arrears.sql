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
-- "Loans In Arrears (All)" — one row per active loan currently in arrears, however long
-- it has been overdue, with a total row at the bottom. The per-loan companion to
-- "Aging Summary (Arrears in Months)": the same loans, unbucketed.
--
-- Parameters:
--   id 5   OfficeIdSelectOne    -> ${officeId}   (the office and every office under it)
--   ${currentUserHierarchy} is injected by the server.
--   No currency parameter: every KC24 loan is in Cambodian riel.
--
-- Over the API: ?R_officeId=1
--
-- Notes:
--   * "In arrears" = an active loan (status 300) with an m_loan_arrears_aging row and a
--     positive total overdue. That table is maintained by the "Update Loan Arrears Ageing"
--     job, so figures are as of its last run.
--   * Days/months are counted from overdue_since_date_derived (the oldest unpaid due date)
--     to the business date; the report runner rewrites CURRENT_DATE to the business date.
--     Months use the Aging Summary's "Up To" rule, so the two reports agree.
--   * Sorted longest-overdue first.
--
-- Run against the TENANT database (fineract_default), not the tenant store.
-- Safe to re-run: creates the report on the first run and only replaces its SQL after
-- that. No restart needed.
-- =====================================================================================

DO $deploy$
DECLARE
    v_report_name text := 'Loans In Arrears (All)';
    v_report_id bigint;

    v_report_sql text := $rpt$
WITH bizdate AS (
    -- the report runner rewrites CURRENT_DATE to the tenant's business date
    SELECT CURRENT_DATE AS d
),
arrears AS (
    SELECT mc.display_name AS client,
           mc.account_no AS client_no,
           mc.mobile_no AS mobile,
           ml.account_no AS loan_no,
           mp.name AS product,
           coalesce(ms.display_name, '-') AS loan_officer,
           ml.disbursedon_date AS disbursed_on,
           laa.overdue_since_date_derived AS overdue_since,
           GREATEST(0, b.d - laa.overdue_since_date_derived) AS days_in_arrears,
           LEAST(13, GREATEST(1,
               (EXTRACT(YEAR FROM age(b.d, laa.overdue_since_date_derived)) * 12
                + EXTRACT(MONTH FROM age(b.d, laa.overdue_since_date_derived))
                + CASE WHEN EXTRACT(DAY FROM age(b.d, laa.overdue_since_date_derived)) > 0 THEN 1 ELSE 0 END)::int)) AS months_in_arrears,
           ml.principal_disbursed_derived AS principal,
           ml.principal_outstanding_derived AS principal_outstanding,
           laa.principal_overdue_derived AS principal_overdue,
           laa.interest_overdue_derived AS interest_overdue,
           coalesce(laa.fee_charges_overdue_derived, 0) + coalesce(laa.penalty_charges_overdue_derived, 0) AS charges_overdue,
           laa.total_overdue_derived AS total_overdue
    FROM m_office mo
    JOIN m_office ounder ON ounder.hierarchy LIKE concat(mo.hierarchy, '%')
                        AND ounder.hierarchy LIKE concat('${currentUserHierarchy}', '%')
    JOIN m_client mc ON mc.office_id = ounder.id
    JOIN m_loan ml ON ml.client_id = mc.id
    JOIN m_loan_arrears_aging laa ON laa.loan_id = ml.id
    CROSS JOIN bizdate b
    LEFT JOIN m_product_loan mp ON mp.id = ml.product_id
    LEFT JOIN m_staff ms ON ms.id = ml.loan_officer_id
    WHERE ml.loan_status_id = 300 /* active */
      AND laa.total_overdue_derived > 0
      AND mo.id = '${officeId}'
)
SELECT "No.", "Client", "Client No", "Mobile", "Loan No", "Product", "Loan Officer",
       "Disbursed On", "Overdue Since", "Days In Arrears", "Months In Arrears (Up To)",
       "Original Principal", "Principal Outstanding", "Principal Overdue", "Interest Overdue",
       "Fees & Penalties Overdue", "Total Overdue"
FROM (
    SELECT 0 AS sort_group,
           row_number() OVER (ORDER BY a.days_in_arrears DESC, a.loan_no) AS "No.",
           a.client AS "Client",
           a.client_no AS "Client No",
           a.mobile AS "Mobile",
           a.loan_no AS "Loan No",
           a.product AS "Product",
           a.loan_officer AS "Loan Officer",
           a.disbursed_on AS "Disbursed On",
           a.overdue_since AS "Overdue Since",
           a.days_in_arrears AS "Days In Arrears",
           CASE WHEN a.months_in_arrears = 13 THEN '12+' ELSE a.months_in_arrears::text END AS "Months In Arrears (Up To)",
           a.principal AS "Original Principal",
           a.principal_outstanding AS "Principal Outstanding",
           a.principal_overdue AS "Principal Overdue",
           a.interest_overdue AS "Interest Overdue",
           a.charges_overdue AS "Fees & Penalties Overdue",
           a.total_overdue AS "Total Overdue"
    FROM arrears a
    UNION ALL
    SELECT 1, NULL,
           concat('Total (', count(*), ' loans)'),
           NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL,
           coalesce(sum(a.principal), 0),
           coalesce(sum(a.principal_outstanding), 0),
           coalesce(sum(a.principal_overdue), 0),
           coalesce(sum(a.interest_overdue), 0),
           coalesce(sum(a.charges_overdue), 0),
           coalesce(sum(a.total_overdue), 0)
    FROM arrears a
) r
ORDER BY sort_group, "No."
$rpt$;
BEGIN
    UPDATE stretchy_report SET report_sql = v_report_sql WHERE report_name = v_report_name
    RETURNING id INTO v_report_id;

    IF v_report_id IS NULL THEN
        INSERT INTO stretchy_report (report_name, report_type, report_subtype, report_category, description,
                                     core_report, use_report, self_service_user_report, report_sql)
        VALUES (v_report_name, 'Table', NULL, 'Loan',
                'Every active loan currently in arrears, longest overdue first, with totals',
                false, true, false, v_report_sql)
        RETURNING id INTO v_report_id;
        RAISE NOTICE 'Report "%" created (id %).', v_report_name, v_report_id;
    ELSE
        RAISE NOTICE 'Report SQL updated for "%".', v_report_name;
    END IF;

    INSERT INTO stretchy_report_parameter (report_id, parameter_id, report_parameter_name)
    SELECT v_report_id, p.id, NULL
    FROM stretchy_parameter p
    WHERE p.parameter_name = 'OfficeIdSelectOne'
      AND NOT EXISTS (SELECT 1 FROM stretchy_report_parameter srp
                      WHERE srp.report_id = v_report_id AND srp.parameter_id = p.id);

    INSERT INTO m_permission (grouping, code, entity_name, action_name, can_maker_checker)
    SELECT 'report', 'READ_' || v_report_name, v_report_name, 'READ', false
    WHERE NOT EXISTS (SELECT 1 FROM m_permission WHERE code = 'READ_' || v_report_name);
END
$deploy$;

-- =====================================================================================
-- Rollback
--
--   DELETE FROM stretchy_report_parameter
--    WHERE report_id = (SELECT id FROM stretchy_report WHERE report_name = 'Loans In Arrears (All)');
--   DELETE FROM m_role_permission
--    WHERE permission_id = (SELECT id FROM m_permission WHERE code = 'READ_Loans In Arrears (All)');
--   DELETE FROM m_permission WHERE code = 'READ_Loans In Arrears (All)';
--   DELETE FROM stretchy_report WHERE report_name = 'Loans In Arrears (All)';
-- =====================================================================================
