/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.fineract.integrationtests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import org.apache.fineract.client.models.PostLoanProductsRequest;
import org.apache.fineract.client.models.PostLoansLoanIdRequest;
import org.apache.fineract.client.models.PostLoansRequest;
import org.apache.fineract.integrationtests.client.feign.FeignLoanTestBase;
import org.apache.fineract.integrationtests.client.feign.modules.LoanRequestBuilders;
import org.apache.fineract.integrationtests.client.feign.modules.LoanTestData.AmortizationType;
import org.apache.fineract.integrationtests.client.feign.modules.LoanTestData.InterestType;
import org.apache.fineract.integrationtests.client.feign.modules.LoanTestData.RepaymentFrequencyType;
import org.junit.jupiter.api.Test;

/**
 * Covers the manual installment amount adjustment on FLAT loans: the officer supplies one target total due per
 * installment and interest is back-solved as {@code target - principal}.
 * <p>
 * The figures mirror the KHR case that motivated the feature: 400,000 over 4 monthly installments at 1.4125% flat per
 * month gives 22,600 total interest, i.e. an awkward 105,650 per installment, which the officer rounds to 105,700.
 */
public class LoanAdjustInstallmentAmountTest extends FeignLoanTestBase {

    private static final double PRINCIPAL = 400000.0;
    private static final int NUMBER_OF_REPAYMENTS = 4;
    private static final double MONTHLY_FLAT_RATE = 1.4125;
    private static final double TARGET_INSTALLMENT = 105700.0;
    private static final double PRINCIPAL_PER_INSTALLMENT = 100000.0;
    private static final double ADJUSTED_INTEREST_PER_INSTALLMENT = 5700.0;

    @Test
    public void adjustedInstallmentAmountIsAppliedAndSurvivesApprovalAndDisbursement() {
        runAt("01 February 2026", () -> {
            final Long loanId = applyFlatLoan();

            // Nominal flat schedule: 100,000 + 5,650 = 105,650 per installment.
            verifyRepaymentSchedule(loanId, //
                    installment(PRINCIPAL, null, "01 February 2026"), //
                    installment(PRINCIPAL_PER_INSTALLMENT, 5650.0, 105650.0, false, "01 March 2026"), //
                    installment(PRINCIPAL_PER_INSTALLMENT, 5650.0, 105650.0, false, "01 April 2026"), //
                    installment(PRINCIPAL_PER_INSTALLMENT, 5650.0, 105650.0, false, "01 May 2026"), //
                    installment(PRINCIPAL_PER_INSTALLMENT, 5650.0, 105650.0, false, "01 June 2026") //
            );

            adjustInstallmentAmount(loanId, TARGET_INSTALLMENT);

            // Every installment now totals exactly 105,700; interest rose 22,600 -> 22,800.
            verifyAdjustedSchedule(loanId);
            assertEquals(0, BigDecimal.valueOf(TARGET_INSTALLMENT).compareTo(getLoanDetails(loanId).getAdjustedInstallmentAmount()));

            // The schedule is regenerated on approval and again on disbursement. This is the regression that a one-off
            // UPDATE of m_loan_repayment_schedule would fail.
            approveLoan(loanId, LoanRequestBuilders.approveLoan(PRINCIPAL, "01 February 2026"));
            verifyAdjustedSchedule(loanId);

            disburseLoan(loanId, BigDecimal.valueOf(PRINCIPAL), "01 February 2026");
            verifyAdjustedSchedule(loanId);
        });
    }

    @Test
    public void installmentAmountSuppliedOnTheApplicationIsHonouredImmediately() {
        runAt("01 February 2026", () -> {
            // The officer types the target on the application form itself, so the very first generated schedule
            // already carries it - no submit-then-correct round trip.
            final Long loanId = applyLoan(InterestType.FLAT, BigDecimal.valueOf(TARGET_INSTALLMENT));

            verifyAdjustedSchedule(loanId);
            assertEquals(0, BigDecimal.valueOf(TARGET_INSTALLMENT).compareTo(getLoanDetails(loanId).getAdjustedInstallmentAmount()));

            // Still survives the regenerations that follow.
            approveLoan(loanId, LoanRequestBuilders.approveLoan(PRINCIPAL, "01 February 2026"));
            disburseLoan(loanId, BigDecimal.valueOf(PRINCIPAL), "01 February 2026");
            verifyAdjustedSchedule(loanId);
        });
    }

    @Test
    public void adjustmentSurvivesUndoApproval() {
        runAt("01 February 2026", () -> {
            final Long loanId = applyFlatLoan();
            adjustInstallmentAmount(loanId, TARGET_INSTALLMENT);

            approveLoan(loanId, LoanRequestBuilders.approveLoan(PRINCIPAL, "01 February 2026"));
            loanHelper.undoApprovalLoan(loanId, new PostLoansLoanIdRequest());

            verifyAdjustedSchedule(loanId);
        });
    }

    @Test
    public void adjustmentIsRejectedOnceTheLoanIsActive() {
        runAt("01 February 2026", () -> {
            final Long loanId = applyFlatLoan();
            approveLoan(loanId, LoanRequestBuilders.approveLoan(PRINCIPAL, "01 February 2026"));
            disburseLoan(loanId, BigDecimal.valueOf(PRINCIPAL), "01 February 2026");

            assertThrows(RuntimeException.class, () -> adjustInstallmentAmount(loanId, TARGET_INSTALLMENT));
        });
    }

    @Test
    public void installmentAmountBelowThePrincipalPortionIsRejected() {
        runAt("01 February 2026", () -> {
            final Long loanId = applyFlatLoan();

            assertThrows(RuntimeException.class, () -> adjustInstallmentAmount(loanId, 50.0));

            // Nothing was persisted, so the nominal schedule is untouched.
            assertNull(getLoanDetails(loanId).getAdjustedInstallmentAmount());
        });
    }

    @Test
    public void adjustmentIsRejectedForDecliningBalanceLoans() {
        runAt("01 February 2026", () -> {
            final Long loanId = applyLoan(InterestType.DECLINING_BALANCE);

            assertThrows(RuntimeException.class, () -> adjustInstallmentAmount(loanId, TARGET_INSTALLMENT));
        });
    }

    private void verifyAdjustedSchedule(final Long loanId) {
        verifyRepaymentSchedule(loanId, //
                installment(PRINCIPAL, null, "01 February 2026"), //
                installment(PRINCIPAL_PER_INSTALLMENT, ADJUSTED_INTEREST_PER_INSTALLMENT, TARGET_INSTALLMENT, false, "01 March 2026"), //
                installment(PRINCIPAL_PER_INSTALLMENT, ADJUSTED_INTEREST_PER_INSTALLMENT, TARGET_INSTALLMENT, false, "01 April 2026"), //
                installment(PRINCIPAL_PER_INSTALLMENT, ADJUSTED_INTEREST_PER_INSTALLMENT, TARGET_INSTALLMENT, false, "01 May 2026"), //
                installment(PRINCIPAL_PER_INSTALLMENT, ADJUSTED_INTEREST_PER_INSTALLMENT, TARGET_INSTALLMENT, false, "01 June 2026") //
        );
    }

    private void adjustInstallmentAmount(final Long loanId, final double installmentAmount) {
        loanHelper.adjustInstallmentAmount(loanId,
                new PostLoansLoanIdRequest().installmentAmount(BigDecimal.valueOf(installmentAmount)).locale("en"));
    }

    private Long applyFlatLoan() {
        return applyLoan(InterestType.FLAT);
    }

    private Long applyLoan(final int interestType) {
        return applyLoan(interestType, null);
    }

    private Long applyLoan(final int interestType, final BigDecimal installmentAmount) {
        final PostLoanProductsRequest product = createOnePeriod30DaysPeriodicAccrualProduct(MONTHLY_FLAT_RATE)//
                .multiDisburseLoan(false)//
                .disallowExpectedDisbursements(false)//
                .allowApprovedDisbursedAmountsOverApplied(false)//
                .overAppliedCalculationType(null)//
                .overAppliedNumber(null)//
                .principal(PRINCIPAL)//
                .minPrincipal(100.0)// the shared template caps principal at 100,000
                .maxPrincipal(1000000.0)//
                .numberOfRepayments(NUMBER_OF_REPAYMENTS)//
                .repaymentEvery(1)//
                .repaymentFrequencyType(RepaymentFrequencyType.MONTHS_L)//
                .interestType(interestType)//
                .amortizationType(AmortizationType.EQUAL_INSTALLMENTS)//
                .graceOnArrearsAgeing(0);

        final Long loanProductId = createLoanProduct(product);
        final Long clientId = createClient();

        final PostLoansRequest applicationRequest = applyLoanRequest(clientId, loanProductId, "01 February 2026", PRINCIPAL,
                NUMBER_OF_REPAYMENTS)//
                .repaymentEvery(1)//
                .loanTermFrequency(NUMBER_OF_REPAYMENTS)//
                .repaymentFrequencyType(RepaymentFrequencyType.MONTHS)//
                .loanTermFrequencyType(RepaymentFrequencyType.MONTHS)//
                .interestRatePerPeriod(BigDecimal.valueOf(MONTHLY_FLAT_RATE))//
                .interestType(interestType)//
                .amortizationType(AmortizationType.EQUAL_INSTALLMENTS)//
                .installmentAmount(installmentAmount);

        return applyForLoan(applicationRequest);
    }
}
