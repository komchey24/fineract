-- =====================================================================================
-- Disbursement and Collection By Loan Officer
--
-- Summary sheet: one row per loan officer (per currency) for a date range, showing
--   * amount disbursed          — DISBURSEMENT transactions posted in the range
--   * total principal collected — principal portion of repayments in the range
--   * total interest collected  — interest portion of repayments in the range
--   * total outstanding         — principal + interest still owed on the officer's
--                                 active loans (a balance, not a range flow)
-- followed by a grand-total row.
--
-- Parameters (all already seeded in stretchy_parameter — nothing new to create):
--   id 5  OfficeIdSelectOne        -> ${officeId}        Office (branch)
--   id 1  startDateSelect          -> ${startDate}       From date
--   id 2  endDateSelect            -> ${endDate}         To date
--   ${currentUserHierarchy} is injected by the server, not registered as a parameter.
--
--   There is no Loan Officer input: every officer in the selected office appears as a
--   row in the output instead.
--
-- Over the API the parameters are passed with the R_ prefix, e.g.
--   ?R_officeId=1&R_startDate=2026-08-01&R_endDate=2026-08-31
--
-- Column headers (Khmer, matching the other deployed reports):
--   ល.រ                  No.
--   មន្ត្រីឥណទាន          Loan Officer
--   រូបិយប័ណ្ណ             Currency
--   ប្រាក់កម្ចីបានផ្តល់      Amount Disbursed
--   ប្រាក់ដើមប្រមូលបាន     Total Principal Collected
--   ការប្រាក់ប្រមូលបាន     Total Interest Collected
--   ទឹកប្រាក់នៅសល់សរុប     Total Outstanding
--
-- Notes:
--   * Disbursement = transaction_type_enum 1, taken at the transaction level so the
--     range filter reflects the actual disbursement date (not the loan's expected date).
--   * Collection transaction types: 2 Repayment, 5 Repayment at disbursement,
--     8 Recovery repayment, 28 Down payment. Reversed rows excluded (is_reversed = false).
--   * Fees/penalties collected are deliberately NOT included — only the principal and
--     interest portions were requested. mlt.amount would include charges/overpayment.
--   * No loan-status filter: a payoff moves the loan to status 600, so filtering on
--     300 (active) would drop exactly the settlements the range needs.
--   * Office scoping uses mlt.office_id (where the transaction was booked). Swap to
--     mc.office_id joined via m_client if you want to scope by the client's branch.
--   * Attribution uses ml.loan_officer_id — the officer assigned to the loan *now*.
--     If officers get reassigned mid-term and you need historical attribution, join
--     m_loan_officer_assignment_history on the transaction date instead.
--   * Loans with no officer assigned are not dropped; they roll up into a '-' row, so
--     the grand total always foots against the branch's actual activity.
--   * Total outstanding is a point-in-time balance taken from m_loan's derived
--     columns, i.e. as of *now* — not as of ${endDate}. Fineract does not keep a
--     dated balance snapshot, so a true as-of-date figure would need the repayment
--     schedule replayed against transactions up to that date.
--   * Only active loans (loan_status_id = 300) carry outstanding. Closed and
--     written-off loans have their derived balances zeroed, so they add nothing.
--   * Outstanding = principal + interest only, deliberately excluding fees and
--     penalties, so the column is consistent with the two collection columns.
--     Swap to ml.total_outstanding_derived if you want charges included.
--   * The outstanding set is UNION ALL'd in as its own rows, not joined: an officer
--     holding a book but with no transactions in the range still gets a row, and the
--     grand total foots against the branch's whole portfolio.
--   * Outstanding is scoped by the client's (or group's) office, since a loan has no
--     office of its own — unlike the transaction columns, which use mlt.office_id.
--   * Grouped by currency so a multi-currency branch does not silently mix amounts;
--     an officer transacting in two currencies gets two rows.
--
-- PostgreSQL only (uses ::date / ::bigint casts and dollar quoting).
-- =====================================================================================


-- 1. The report -------------------------------------------------------------
INSERT INTO stretchy_report
    (report_name, report_type, report_subtype, report_category,
     description, core_report, use_report, self_service_user_report, report_sql)
VALUES
    ('Disbursement and Collection By Loan Officer', 'Table', NULL, 'Loan',
     'Amount disbursed, total principal collected, total interest collected and total outstanding per loan officer for a date range',
     false, true, false,
$rpt$
WITH tx AS (
    SELECT
        COALESCE(ms.display_name, '-')                         AS loan_officer,
        ml.currency_code                                       AS ccy,
        CASE WHEN mlt.transaction_type_enum = 1
             THEN COALESCE(mlt.amount, 0) ELSE 0 END           AS disbursed,
        CASE WHEN mlt.transaction_type_enum IN (2, 5, 8, 28)
             THEN COALESCE(mlt.principal_portion_derived, 0)
             ELSE 0 END                                        AS principal,
        CASE WHEN mlt.transaction_type_enum IN (2, 5, 8, 28)
             THEN COALESCE(mlt.interest_portion_derived, 0)
             ELSE 0 END                                        AS interest,
        0::numeric                                             AS outstanding
    FROM m_loan_transaction mlt
    JOIN m_loan   ml     ON ml.id = mlt.loan_id
    JOIN m_office ounder ON ounder.id = mlt.office_id
    JOIN m_office mo     ON ounder.hierarchy LIKE CONCAT(mo.hierarchy, '%')
    LEFT JOIN m_staff ms ON ms.id = ml.loan_officer_id
    WHERE mo.id = ${officeId}
      AND ounder.hierarchy LIKE CONCAT('${currentUserHierarchy}', '%')
      AND mlt.is_reversed = false
      AND mlt.transaction_type_enum IN (1, 2, 5, 8, 28)
      AND mlt.transaction_date BETWEEN '${startDate}'::date AND '${endDate}'::date
),
outst AS (
    SELECT
        COALESCE(ms.display_name, '-')                         AS loan_officer,
        ml.currency_code                                       AS ccy,
        0::numeric                                             AS disbursed,
        0::numeric                                             AS principal,
        0::numeric                                             AS interest,
        COALESCE(ml.principal_outstanding_derived, 0)
      + COALESCE(ml.interest_outstanding_derived, 0)           AS outstanding
    FROM m_loan ml
    LEFT JOIN m_client mc ON mc.id = ml.client_id
    LEFT JOIN m_group  mg ON mg.id = ml.group_id
    JOIN m_office ounder ON ounder.id = COALESCE(mc.office_id, mg.office_id)
    JOIN m_office mo     ON ounder.hierarchy LIKE CONCAT(mo.hierarchy, '%')
    LEFT JOIN m_staff ms ON ms.id = ml.loan_officer_id
    WHERE mo.id = ${officeId}
      AND ounder.hierarchy LIKE CONCAT('${currentUserHierarchy}', '%')
      AND ml.loan_status_id = 300
),
agg AS (
    SELECT
        loan_officer,
        ccy,
        SUM(disbursed)   AS disbursed,
        SUM(principal)   AS principal,
        SUM(interest)    AS interest,
        SUM(outstanding) AS outstanding
    FROM (
        SELECT * FROM tx
        UNION ALL
        SELECT * FROM outst
    ) u
    GROUP BY loan_officer, ccy
)
SELECT
    ROW_NUMBER() OVER (ORDER BY loan_officer, ccy)::bigint AS "ល.រ",
    loan_officer  AS "មន្ត្រីឥណទាន",
    ccy           AS "រូបិយប័ណ្ណ",
    disbursed     AS "ប្រាក់កម្ចីបានផ្តល់",
    principal     AS "ប្រាក់ដើមប្រមូលបាន",
    interest      AS "ការប្រាក់ប្រមូលបាន",
    outstanding   AS "ទឹកប្រាក់នៅសល់សរុប"
FROM agg
UNION ALL
SELECT
    NULL::bigint,
    'សរុប',
    NULL::text,
    COALESCE(SUM(disbursed), 0),
    COALESCE(SUM(principal), 0),
    COALESCE(SUM(interest), 0),
    COALESCE(SUM(outstanding), 0)
FROM agg
ORDER BY 1 NULLS LAST
$rpt$
);


-- 2. Bind the parameters ----------------------------------------------------
INSERT INTO stretchy_report_parameter (report_id, parameter_id, report_parameter_name)
SELECT r.id, p.parameter_id, p.report_parameter_name
FROM stretchy_report r
CROSS JOIN (VALUES
        (5,  'officeId'),
        (1,  'startDate'),
        (2,  'endDate')
    ) AS p(parameter_id, report_parameter_name)
WHERE r.report_name = 'Disbursement and Collection By Loan Officer'
  AND NOT EXISTS (
        SELECT 1 FROM stretchy_report_parameter srp
        WHERE srp.report_id = r.id AND srp.parameter_id = p.parameter_id);


-- 3. Permission (without this the report returns 403) ------------------------
INSERT INTO m_permission (grouping, code, entity_name, action_name, can_maker_checker)
SELECT 'report', 'READ_Disbursement and Collection By Loan Officer',
       'Disbursement and Collection By Loan Officer', 'READ', false
WHERE NOT EXISTS (
        SELECT 1 FROM m_permission
        WHERE code = 'READ_Disbursement and Collection By Loan Officer');


-- =====================================================================================
-- Rollback / re-apply
--
--   DELETE FROM stretchy_report_parameter
--    WHERE report_id = (SELECT id FROM stretchy_report
--                        WHERE report_name = 'Disbursement and Collection By Loan Officer');
--   DELETE FROM stretchy_report
--    WHERE report_name = 'Disbursement and Collection By Loan Officer';
--   DELETE FROM m_permission
--    WHERE code = 'READ_Disbursement and Collection By Loan Officer';
--
-- To tweak only the query afterwards, keep the report row and run:
--
--   UPDATE stretchy_report SET report_sql = $rpt$ <new SELECT> $rpt$
--    WHERE report_name = 'Disbursement and Collection By Loan Officer';
-- =====================================================================================
