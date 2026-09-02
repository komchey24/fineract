-- =====================================================================================
-- EOD Collection By Loan Officer (Excl. Disbursement Fee)
--   —  អតិថិជនបង់ប្រាក់ និងបង់ផ្តាច់ (មិនរាប់បញ្ចូលកម្រៃដកប្រាក់)
--
-- Variant of sample/loan-officer-eod-report.sql that leaves disbursement fees out of the
-- collection sheet, so the totals reflect only money collected against the repayment
-- schedule — not charges netted off at disbursement time.
--
-- WHAT CHANGED vs. the base report (one line):
--   transaction_type_enum IN (2, 5, 8, 28)   ->   IN (2, 8, 28)
--
-- Why: in Fineract a charge with charge_time_enum = 1 (Disbursement) is posted as a
-- REPAYMENT_AT_DISBURSEMENT transaction, LoanTransactionType id 5. It is not cash the
-- officer collected in the field, it is a fee withheld when the loan was paid out, so it
-- inflates both សោហ៊ុយ and សរុប on an EOD cash sheet. The remaining types are all genuine
-- collections: 2 Repayment, 8 Recovery repayment, 28 Down payment.
--
-- If instead you meant "keep the transaction but zero out its fee portion", do not use
-- this file — go back to the base report and subtract the disbursement-charge amount from
-- the charges expression via m_loan_charge (charge_time_enum = 1). Ask if you want that
-- version; it is a different query, not a filter change.
--
-- Parameters (identical to the base report — all seeded already):
--   id 5  OfficeIdSelectOne        -> ${officeId}        Office (branch)
--   id 6  loanOfficerIdSelectAll   -> ${loanOfficerId}   Loan Officer  (-1 = all)
--   id 1  startDateSelect          -> ${startDate}       From date
--   id 2  endDateSelect            -> ${endDate}         To date
--   ${currentUserHierarchy} is injected by the server, not registered as a parameter.
--
-- Over the API:
--   ?R_officeId=1&R_loanOfficerId=-1&R_startDate=2026-08-06&R_endDate=2026-08-06
--
-- All other design notes from the base report still apply: no loan-status filter (payoffs
-- move the loan to 600), office scoping on mlt.office_id, group loans coalesced,
-- overpayment already included in mlt.amount, single-currency assumption for the total row.
--
-- PostgreSQL only (uses ::date / ::bigint casts and dollar quoting).
-- =====================================================================================


-- 1. The report -------------------------------------------------------------
INSERT INTO stretchy_report
    (report_name, report_type, report_subtype, report_category,
     description, core_report, use_report, self_service_user_report, report_sql)
VALUES
    ('EOD Collection By Loan Officer (Excl. Disbursement Fee)', 'Table', NULL, 'Loan',
     'End-of-day collections per loan officer with principal / interest / charges breakdown and grand total, excluding fees collected at disbursement',
     false, true, false,
$rpt$
WITH tx AS (
    SELECT
        mlt.transaction_date                                   AS tx_date,
        LTRIM(ml.account_no, '0')                              AS loan_no,
        LTRIM(COALESCE(mc.account_no, mg.account_no), '0')     AS client_code,
        COALESCE(mc.display_name, mg.display_name, '-')        AS client_name,
        COALESCE(ms.display_name, '-')                         AS loan_officer,
        CASE mlt.transaction_type_enum
             WHEN 2  THEN 'Repay'
             WHEN 8  THEN 'Recovery'
             WHEN 28 THEN 'Down Payment'
        END                                                    AS tx_type,
        CASE ml.repayment_period_frequency_enum
             WHEN 0 THEN 'ថ្ងៃ'
             WHEN 1 THEN 'អាទិត្យ'
             WHEN 2 THEN 'ខែ'
             WHEN 3 THEN 'ឆ្នាំ'
             ELSE '-'
        END                                                    AS loan_freq,
        ml.currency_code                                       AS ccy,
        COALESCE(mlt.principal_portion_derived, 0)             AS principal,
        COALESCE(mlt.interest_portion_derived, 0)              AS interest,
        COALESCE(mlt.fee_charges_portion_derived, 0)
      + COALESCE(mlt.penalty_charges_portion_derived, 0)       AS charges,
        COALESCE(mlt.overpayment_portion_derived, 0)           AS overpaid,
        mlt.amount                                             AS total
    FROM m_loan_transaction mlt
    JOIN m_loan   ml     ON ml.id = mlt.loan_id
    JOIN m_office ounder ON ounder.id = mlt.office_id
    JOIN m_office mo     ON ounder.hierarchy LIKE CONCAT(mo.hierarchy, '%')
    LEFT JOIN m_client mc ON mc.id = ml.client_id
    LEFT JOIN m_group  mg ON mg.id = ml.group_id
    LEFT JOIN m_staff  ms ON ms.id = ml.loan_officer_id
    WHERE mo.id = ${officeId}
      AND ounder.hierarchy LIKE CONCAT('${currentUserHierarchy}', '%')
      AND (COALESCE(ml.loan_officer_id, -10) = ${loanOfficerId} OR ${loanOfficerId} = -1)
      AND mlt.is_reversed = false
      AND mlt.transaction_type_enum IN (2, 8, 28)
      AND mlt.transaction_date BETWEEN '${startDate}'::date AND '${endDate}'::date
)
SELECT
    ROW_NUMBER() OVER (ORDER BY loan_officer, tx_date, loan_no)::bigint AS "ល.រ",
    tx_date       AS "កាលបរិច្ឆេទ",
    loan_no       AS "កិច្ចសន្យា",
    client_code   AS "កូដអតិថិជន",
    client_name   AS "អតិថិជន",
    loan_officer  AS "មន្ត្រីឥណទាន",
    tx_type       AS "ប្រភេទប្រតិបត្តិការ",
    loan_freq     AS "ប្រភេទកម្ចី",
    ccy           AS "រូបិយប័ណ្ណ",
    principal     AS "ប្រាក់ដើម",
    interest      AS "ការប្រាក់",
    charges       AS "សោហ៊ុយ",
    overpaid      AS "បង់ទុក",
    total         AS "សរុប"
FROM tx
UNION ALL
SELECT
    NULL::bigint, NULL::date, NULL::text, NULL::text, NULL::text,
    NULL::text, NULL::text, NULL::text,
    'សរុប',
    SUM(principal), SUM(interest), SUM(charges), SUM(overpaid), SUM(total)
FROM tx
ORDER BY 1 NULLS LAST
$rpt$
);


-- 2. Bind the parameters ----------------------------------------------------
INSERT INTO stretchy_report_parameter (report_id, parameter_id, report_parameter_name)
SELECT r.id, p.parameter_id, p.report_parameter_name
FROM stretchy_report r
CROSS JOIN (VALUES
        (5,  'officeId'),
        (6,  'loanOfficerId'),
        (1,  'startDate'),
        (2,  'endDate')
    ) AS p(parameter_id, report_parameter_name)
WHERE r.report_name = 'EOD Collection By Loan Officer (Excl. Disbursement Fee)'
  AND NOT EXISTS (
        SELECT 1 FROM stretchy_report_parameter srp
        WHERE srp.report_id = r.id AND srp.parameter_id = p.parameter_id);


-- 3. Permission (without this the report returns 403) ------------------------
INSERT INTO m_permission (grouping, code, entity_name, action_name, can_maker_checker)
SELECT 'report', 'READ_EOD Collection By Loan Officer (Excl. Disbursement Fee)',
       'EOD Collection By Loan Officer (Excl. Disbursement Fee)', 'READ', false
WHERE NOT EXISTS (
        SELECT 1 FROM m_permission
        WHERE code = 'READ_EOD Collection By Loan Officer (Excl. Disbursement Fee)');


-- =====================================================================================
-- Rollback / re-apply
--
--   DELETE FROM stretchy_report_parameter
--    WHERE report_id = (SELECT id FROM stretchy_report
--                        WHERE report_name = 'EOD Collection By Loan Officer (Excl. Disbursement Fee)');
--   DELETE FROM stretchy_report
--    WHERE report_name = 'EOD Collection By Loan Officer (Excl. Disbursement Fee)';
--   DELETE FROM m_permission
--    WHERE code = 'READ_EOD Collection By Loan Officer (Excl. Disbursement Fee)';
--
-- To tweak only the query afterwards, keep the report row and run:
--
--   UPDATE stretchy_report SET report_sql = $rpt$ <new SELECT> $rpt$
--    WHERE report_name = 'EOD Collection By Loan Officer (Excl. Disbursement Fee)';
-- =====================================================================================