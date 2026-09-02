-- =====================================================================================
-- Collection / expected-repayment report  —  តារាងប្រមូលប្រាក់
--
-- 19 columns, Khmer headers, matching sample/expect-repayment.pdf.
-- Only the SELECT list changed; the sched/inst CTEs, joins, WHERE and ORDER BY are
-- identical to the previous version.
--
-- Dropped vs. previous version: "Status", "Office", "Loan Officer".
-- (Late/not-late and loan-officer ordering are still applied — just not displayed.)
--
-- Parameters (already registered in stretchy_report_parameter — nothing new to add):
--   ${startDate} ${endDate} ${officeId} ${loanOfficerId}   + server-injected ${currentUserHierarchy}
--
-- PostgreSQL only (uses ::date casts).
-- =====================================================================================

WITH sched AS (
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
    sched.principal_due + sched.interest_due          AS "សរុប"
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


-- =====================================================================================
-- To apply directly against the tenant DB, fill in the report name and run the
-- statement below. Dollar-quoting ($rpt$) means none of the single quotes above
-- need escaping — paste the SELECT verbatim between the markers.
--
-- Find the report name first:
--   SELECT id, report_name, report_category FROM stretchy_report
--    WHERE report_sql LIKE '%next_duedate%';
--
-- UPDATE stretchy_report
--    SET report_sql = $rpt$
--        <paste the whole SELECT above here>
--    $rpt$
--  WHERE report_name = '<report name>';
-- =====================================================================================
