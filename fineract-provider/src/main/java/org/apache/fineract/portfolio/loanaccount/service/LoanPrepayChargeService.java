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
package org.apache.fineract.portfolio.loanaccount.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.infrastructure.core.domain.ExternalId;
import org.apache.fineract.infrastructure.core.service.DateUtils;
import org.apache.fineract.infrastructure.core.service.MathUtil;
import org.apache.fineract.infrastructure.event.business.domain.loan.LoanBalanceChangedBusinessEvent;
import org.apache.fineract.infrastructure.event.business.domain.loan.charge.LoanAddChargeBusinessEvent;
import org.apache.fineract.infrastructure.event.business.service.BusinessEventNotifierService;
import org.apache.fineract.organisation.monetary.domain.Money;
import org.apache.fineract.organisation.monetary.domain.MoneyHelper;
import org.apache.fineract.portfolio.charge.domain.Charge;
import org.apache.fineract.portfolio.charge.domain.ChargeCalculationType;
import org.apache.fineract.portfolio.charge.domain.ChargeTimeType;
import org.apache.fineract.portfolio.loanaccount.data.OutstandingAmountsDTO;
import org.apache.fineract.portfolio.loanaccount.data.ScheduleGeneratorDTO;
import org.apache.fineract.portfolio.loanaccount.domain.Loan;
import org.apache.fineract.portfolio.loanaccount.domain.LoanAccountDomainServiceJpaHelper;
import org.apache.fineract.portfolio.loanaccount.domain.LoanCharge;
import org.apache.fineract.portfolio.loanaccount.domain.LoanChargeRepository;
import org.springframework.stereotype.Service;

/**
 * Applies the {@link ChargeTimeType#PREPAY_LOAN} penalty: a loan product may carry a single penalty charge that falls
 * due when the borrower settles the loan in full before its maturity date.
 * <p>
 * The charge is never attached to the loan up front. It is quoted on the prepayment template (so the amount the
 * borrower is asked for already includes it) and then materialised as a real {@link LoanCharge} on the loan just before
 * the settling repayment is allocated, so that the repayment pays it off along with the rest of the balance.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LoanPrepayChargeService {

    private final LoanChargeService loanChargeService;
    private final LoanChargeRepository loanChargeRepository;
    private final LoanTransactionProcessingService loanTransactionProcessingService;
    private final LoanAccountDomainServiceJpaHelper loanAccountDomainServiceJpaHelper;
    private final BusinessEventNotifierService businessEventNotifierService;

    /**
     * The prepay penalty the borrower would owe on top of {@code outstandingAmounts} when settling on {@code onDate},
     * or {@link BigDecimal#ZERO} when the loan carries no prepay charge or is not being settled early.
     */
    public BigDecimal calculatePrepayChargeAmount(final Loan loan, final LocalDate onDate, final OutstandingAmountsDTO outstandingAmounts) {
        return findApplicablePrepayCharge(loan, onDate) //
                .map(charge -> calculateChargeAmount(loan, charge, outstandingAmounts)) //
                .orElse(BigDecimal.ZERO);
    }

    /**
     * Adds the prepay penalty to the loan when {@code repaymentAmount} settles the whole outstanding balance ahead of
     * maturity. Must be called before the repayment transaction is allocated, otherwise the repayment cannot cover the
     * charge it just triggered.
     */
    public void applyPrepayChargeIfApplicable(final Loan loan, final LocalDate transactionDate, final Money repaymentAmount,
            final ScheduleGeneratorDTO scheduleGeneratorDTO) {
        final Optional<Charge> prepayCharge = findApplicablePrepayCharge(loan, transactionDate);
        if (prepayCharge.isEmpty()) {
            return;
        }

        final OutstandingAmountsDTO outstandingAmounts = fetchOutstandingAmounts(loan, transactionDate, scheduleGeneratorDTO);
        if (outstandingAmounts == null) {
            return;
        }
        if (repaymentAmount.isLessThan(outstandingAmounts.getTotalOutstanding())) {
            // a partial repayment is not a prepayment of the loan
            return;
        }

        final BigDecimal chargeAmount = calculateChargeAmount(loan, prepayCharge.get(), outstandingAmounts);
        if (!MathUtil.isGreaterThanZero(chargeAmount)) {
            return;
        }

        final LoanCharge loanCharge = loanChargeService.create(loan, prepayCharge.get(),
                percentageBase(loan, prepayCharge.get(), outstandingAmounts), prepayCharge.get().getAmount(), ChargeTimeType.PREPAY_LOAN,
                ChargeCalculationType.fromInt(prepayCharge.get().getChargeCalculation()), transactionDate, null, null, chargeAmount,
                ExternalId.empty());

        businessEventNotifierService.notifyPreBusinessEvent(new LoanAddChargeBusinessEvent(loanCharge));
        loanChargeService.addLoanCharge(loan, loanCharge);
        loanChargeRepository.saveAndFlush(loanCharge);
        businessEventNotifierService.notifyPostBusinessEvent(new LoanAddChargeBusinessEvent(loanCharge));
        businessEventNotifierService.notifyPostBusinessEvent(new LoanBalanceChangedBusinessEvent(loan));

        log.debug("Applied prepay charge {} of {} to loan {} settled on {}", prepayCharge.get().getName(), chargeAmount, loan.getId(),
                transactionDate);
    }

    private OutstandingAmountsDTO fetchOutstandingAmounts(final Loan loan, final LocalDate transactionDate,
            final ScheduleGeneratorDTO scheduleGeneratorDTO) {
        if (!loan.isInterestBearingAndInterestRecalculationEnabled()) {
            // without interest recalculation the prepayment detail is a plain sum over the schedule, safe to take here
            return loanTransactionProcessingService.fetchPrepaymentDetail(scheduleGeneratorDTO, transactionDate, loan);
        }
        // otherwise it reprocesses the schedule and the charges in place, so it has to run off entities we are not
        // about to write
        return loanAccountDomainServiceJpaHelper.fetchPrepaymentDetailInIsolation(loan.getId(), transactionDate, scheduleGeneratorDTO);
    }

    private Optional<Charge> findApplicablePrepayCharge(final Loan loan, final LocalDate onDate) {
        if (loan == null || loan.getLoanProduct() == null || onDate == null) {
            return Optional.empty();
        }
        // settling on or after maturity is not a prepayment
        final LocalDate maturityDate = loan.getMaturityDate();
        if (maturityDate == null || !DateUtils.isBefore(onDate, maturityDate)) {
            return Optional.empty();
        }
        // the penalty is only ever raised once per loan
        if (loan.getLoanCharges() != null && loan.getLoanCharges().stream().anyMatch(lc -> lc.isActive() && lc.isPrepayLoanCharge())) {
            return Optional.empty();
        }
        return loan.getLoanProduct().getCharges().stream()
                .filter(charge -> charge.isActive() && !charge.isDeleted() && charge.isLoanCharge() && charge.isPrepayLoan()) //
                .findFirst();
    }

    private BigDecimal calculateChargeAmount(final Loan loan, final Charge charge, final OutstandingAmountsDTO outstandingAmounts) {
        final ChargeCalculationType calculationType = ChargeCalculationType.fromInt(charge.getChargeCalculation());
        if (calculationType.isFlat()) {
            return charge.getAmount();
        }
        final BigDecimal amount = MathUtil.percentageOf(percentageBase(loan, charge, outstandingAmounts), charge.getAmount(),
                MoneyHelper.getMathContext());
        return Money.of(loan.getCurrency(), applyCaps(charge, amount)).getAmount();
    }

    /**
     * A prepay penalty is priced off what the borrower still owes on the settlement date, not off the disbursed
     * principal or the interest of the full original term.
     */
    private BigDecimal percentageBase(final Loan loan, final Charge charge, final OutstandingAmountsDTO outstandingAmounts) {
        final BigDecimal principal = outstandingAmounts.principal().getAmount();
        final BigDecimal interest = outstandingAmounts.interest().getAmount();
        return switch (ChargeCalculationType.fromInt(charge.getChargeCalculation())) {
            case PERCENT_OF_AMOUNT, PERCENT_OF_DISBURSEMENT_AMOUNT -> principal;
            case PERCENT_OF_AMOUNT_AND_INTEREST -> MathUtil.add(principal, interest);
            case PERCENT_OF_INTEREST -> interest;
            case INVALID, FLAT -> BigDecimal.ZERO;
        };
    }

    private BigDecimal applyCaps(final Charge charge, final BigDecimal amount) {
        BigDecimal cappedAmount = amount;
        if (charge.getMinCap() != null && MathUtil.isLessThan(cappedAmount, charge.getMinCap())) {
            cappedAmount = charge.getMinCap();
        }
        if (charge.getMaxCap() != null && MathUtil.isGreaterThan(cappedAmount, charge.getMaxCap())) {
            cappedAmount = charge.getMaxCap();
        }
        return cappedAmount;
    }
}
