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
package org.apache.fineract.portfolio.loanaccount.loanschedule.domain;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Map;
import java.util.TreeMap;
import org.apache.fineract.organisation.monetary.domain.Money;
import org.apache.fineract.organisation.monetary.mapper.CurrencyMapper;
import org.apache.fineract.portfolio.loanaccount.data.LoanTermVariationsData;
import org.apache.fineract.portfolio.loanaccount.domain.LoanTransactionRepository;
import org.springframework.stereotype.Component;

@Component
public class CumulativeFlatInterestLoanScheduleGenerator extends AbstractCumulativeLoanScheduleGenerator {

    private final ScheduledDateGenerator scheduledDateGenerator;
    private final PaymentPeriodsInOneYearCalculator paymentPeriodsInOneYearCalculator;

    public CumulativeFlatInterestLoanScheduleGenerator(final ScheduledDateGenerator scheduledDateGenerator,
            final PaymentPeriodsInOneYearCalculator paymentPeriodsInOneYearCalculator,
            final LoanTransactionRepository loanTransactionRepository, final CurrencyMapper currencyMapper) {
        super(loanTransactionRepository, currencyMapper);
        this.scheduledDateGenerator = scheduledDateGenerator;
        this.paymentPeriodsInOneYearCalculator = paymentPeriodsInOneYearCalculator;
    }

    @Override
    public ScheduledDateGenerator getScheduledDateGenerator() {
        return scheduledDateGenerator;
    }

    @Override
    public PaymentPeriodsInOneYearCalculator getPaymentPeriodsInOneYearCalculator() {
        return paymentPeriodsInOneYearCalculator;
    }

    @Override
    public PrincipalInterest calculatePrincipalInterestComponentsForPeriod(final PaymentPeriodsInOneYearCalculator calculator,
            final BigDecimal interestCalculationGraceOnRepaymentPeriodFraction, final Money totalCumulativePrincipal,
            Money totalCumulativeInterest, Money totalInterestDueForLoan, final Money cumulatingInterestPaymentDueToGrace,
            final Money outstandingBalance, final LoanApplicationTerms loanApplicationTerms, final int periodNumber, final MathContext mc,
            @SuppressWarnings("unused") TreeMap<LocalDate, Money> principalVariation,
            @SuppressWarnings("unused") Map<LocalDate, Money> compoundingMap, LocalDate periodStartDate, LocalDate periodEndDate,
            @SuppressWarnings("unused") Collection<LoanTermVariationsData> termVariations) {

        if (loanApplicationTerms.isInstallmentAmountAdjusted()) {
            // Manual installment amount adjustment. The ordering here is deliberately inverted compared to the
            // standard path below: normally interest is computed first and principal is derived from it, but here
            // principal is authoritative and interest is back-solved from the officer supplied installment total.
            // Principal therefore has to be fully settled - including the last period rounding correction - before
            // interest is derived, otherwise the final installment would not land exactly on the requested amount.
            //
            // Interest is passed as null to calculateTotalPrincipalForPeriod on purpose. Its only two consumers for
            // FLAT loans are the installmentAmountInMultiplesOf block, which guards on null and then simply rounds
            // principal (exactly what we want), and the fixed EMI branch, which is unreachable because a fixed EMI
            // amount is rejected up front by the validator.
            Money adjustedPrincipal = loanApplicationTerms.calculateTotalPrincipalForPeriod(calculator, outstandingBalance, periodNumber,
                    mc, null);
            adjustedPrincipal = loanApplicationTerms.adjustPrincipalIfLastRepaymentPeriod(adjustedPrincipal,
                    totalCumulativePrincipal.plus(adjustedPrincipal), periodNumber);

            // adjustInterestIfLastRepaymentPeriod is intentionally skipped: the loan level total interest has already
            // been overridden to (installmentAmount * numberOfRepayments - principal), so the cumulative interest
            // lands on it exactly and the correction could only be a no-op or a source of drift.
            final Money adjustedInterest = loanApplicationTerms.calculateAdjustedInterestForPeriod(adjustedPrincipal);

            return new PrincipalInterest(adjustedPrincipal, adjustedInterest, cumulatingInterestPaymentDueToGrace);
        }

        final PrincipalInterest result = loanApplicationTerms.calculateTotalInterestForPeriod(calculator,
                interestCalculationGraceOnRepaymentPeriodFraction, periodNumber, mc, cumulatingInterestPaymentDueToGrace,
                outstandingBalance, periodStartDate, periodEndDate);
        Money interestForThisInstallment = result.interest();

        Money principalForThisInstallment = loanApplicationTerms.calculateTotalPrincipalForPeriod(calculator, outstandingBalance,
                periodNumber, mc, interestForThisInstallment);

        // update cumulative fields for principal & interest
        final Money interestBroughtForwardDueToGrace = result.interestPaymentDueToGrace();
        final Money totalCumulativePrincipalToDate = totalCumulativePrincipal.plus(principalForThisInstallment);
        final Money totalCumulativeInterestToDate = totalCumulativeInterest.plus(interestForThisInstallment);

        // adjust if needed
        principalForThisInstallment = loanApplicationTerms.adjustPrincipalIfLastRepaymentPeriod(principalForThisInstallment,
                totalCumulativePrincipalToDate, periodNumber);

        // totalCumulativeInterest from partial schedule generation for multi
        // rescheduling
        /*
         * if (loanApplicationTerms.getPartialTotalCumulativeInterest() != null &&
         * loanApplicationTerms.getTotalInterestDue() != null) { totalInterestDueForLoan =
         * loanApplicationTerms.getTotalInterestDue(); totalInterestDueForLoan =
         * totalInterestDueForLoan.plus(loanApplicationTerms. getPartialTotalCumulativeInterest()); }
         */
        interestForThisInstallment = loanApplicationTerms.adjustInterestIfLastRepaymentPeriod(interestForThisInstallment,
                totalCumulativeInterestToDate, totalInterestDueForLoan, periodNumber);

        return new PrincipalInterest(principalForThisInstallment, interestForThisInstallment, interestBroughtForwardDueToGrace);
    }
}
