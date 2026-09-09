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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.apache.fineract.infrastructure.event.business.service.BusinessEventNotifierService;
import org.apache.fineract.organisation.monetary.domain.MonetaryCurrency;
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
import org.apache.fineract.portfolio.loanproduct.domain.LoanProduct;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class LoanPrepayChargeServiceTest {

    private static final LocalDate MATURITY_DATE = LocalDate.of(2024, 12, 31);
    private static final LocalDate BEFORE_MATURITY = LocalDate.of(2024, 6, 30);

    private final MonetaryCurrency currency = new MonetaryCurrency("USD", 2, null);

    @Mock
    private LoanChargeService loanChargeService;
    @Mock
    private LoanChargeRepository loanChargeRepository;
    @Mock
    private LoanTransactionProcessingService loanTransactionProcessingService;
    @Mock
    private LoanAccountDomainServiceJpaHelper loanAccountDomainServiceJpaHelper;
    @Mock
    private BusinessEventNotifierService businessEventNotifierService;

    @Mock
    private Loan loan;
    @Mock
    private LoanProduct loanProduct;
    @Mock
    private Charge prepayCharge;
    @Mock
    private ScheduleGeneratorDTO scheduleGeneratorDTO;

    @InjectMocks
    private LoanPrepayChargeService underTest;

    private MockedStatic<MoneyHelper> moneyHelper;

    @BeforeEach
    public void setUp() {
        moneyHelper = Mockito.mockStatic(MoneyHelper.class);
        moneyHelper.when(MoneyHelper::getRoundingMode).thenReturn(RoundingMode.HALF_EVEN);
        moneyHelper.when(MoneyHelper::getMathContext).thenReturn(new MathContext(12, RoundingMode.HALF_EVEN));

        when(loan.getId()).thenReturn(1L);
        when(loan.getCurrency()).thenReturn(currency);
        when(loan.getMaturityDate()).thenReturn(MATURITY_DATE);
        when(loan.getLoanProduct()).thenReturn(loanProduct);
        when(loan.getLoanCharges()).thenReturn(Set.of());
        when(loan.isInterestBearingAndInterestRecalculationEnabled()).thenReturn(false);

        when(prepayCharge.isActive()).thenReturn(true);
        when(prepayCharge.isDeleted()).thenReturn(false);
        when(prepayCharge.isLoanCharge()).thenReturn(true);
        when(prepayCharge.isPrepayLoan()).thenReturn(true);
        when(loanProduct.getCharges()).thenReturn(List.of(prepayCharge));
    }

    @AfterEach
    public void tearDown() {
        moneyHelper.close();
    }

    @Test
    public void flatChargeIsQuotedWhenSettlingBeforeMaturity() {
        givenFlatCharge(BigDecimal.valueOf(25));

        assertEquals(0,
                BigDecimal.valueOf(25).compareTo(underTest.calculatePrepayChargeAmount(loan, BEFORE_MATURITY, outstanding(500, 50))));
    }

    @Test
    public void percentageChargeIsPricedOffTheOutstandingPrincipal() {
        givenPercentageCharge(ChargeCalculationType.PERCENT_OF_AMOUNT, BigDecimal.valueOf(2));

        // 2% of the 500 principal still outstanding, not of the disbursed principal
        assertEquals(0,
                BigDecimal.valueOf(10).compareTo(underTest.calculatePrepayChargeAmount(loan, BEFORE_MATURITY, outstanding(500, 50))));
    }

    @Test
    public void percentageChargeIsCapped() {
        givenPercentageCharge(ChargeCalculationType.PERCENT_OF_AMOUNT, BigDecimal.valueOf(2));
        when(prepayCharge.getMaxCap()).thenReturn(BigDecimal.valueOf(4));

        assertEquals(0,
                BigDecimal.valueOf(4).compareTo(underTest.calculatePrepayChargeAmount(loan, BEFORE_MATURITY, outstanding(500, 50))));
    }

    @Test
    public void nothingIsQuotedOnOrAfterMaturity() {
        givenFlatCharge(BigDecimal.valueOf(25));

        assertEquals(0, BigDecimal.ZERO.compareTo(underTest.calculatePrepayChargeAmount(loan, MATURITY_DATE, outstanding(500, 50))));
    }

    @Test
    public void nothingIsQuotedWhenTheProductHasNoPrepayCharge() {
        givenFlatCharge(BigDecimal.valueOf(25));
        when(loanProduct.getCharges()).thenReturn(List.of());

        assertEquals(0, BigDecimal.ZERO.compareTo(underTest.calculatePrepayChargeAmount(loan, BEFORE_MATURITY, outstanding(500, 50))));
    }

    @Test
    public void nothingIsQuotedWhenThePenaltyWasAlreadyRaised() {
        givenFlatCharge(BigDecimal.valueOf(25));
        LoanCharge existing = Mockito.mock(LoanCharge.class);
        when(existing.isActive()).thenReturn(true);
        when(existing.isPrepayLoanCharge()).thenReturn(true);
        when(loan.getLoanCharges()).thenReturn(Set.of(existing));

        assertEquals(0, BigDecimal.ZERO.compareTo(underTest.calculatePrepayChargeAmount(loan, BEFORE_MATURITY, outstanding(500, 50))));
    }

    @Test
    public void chargeIsAppliedWhenTheRepaymentSettlesTheLoanEarly() {
        givenFlatCharge(BigDecimal.valueOf(25));
        final OutstandingAmountsDTO outstanding = outstanding(500, 50);
        when(loanTransactionProcessingService.fetchPrepaymentDetail(scheduleGeneratorDTO, BEFORE_MATURITY, loan)).thenReturn(outstanding);
        LoanCharge created = Mockito.mock(LoanCharge.class);
        when(loanChargeService.create(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(created);

        underTest.applyPrepayChargeIfApplicable(loan, BEFORE_MATURITY, Money.of(currency, BigDecimal.valueOf(575)), scheduleGeneratorDTO);

        verify(loanChargeService).addLoanCharge(loan, created);
        verify(loanChargeRepository).saveAndFlush(created);
    }

    @Test
    public void chargeIsNotAppliedForAPartialRepayment() {
        givenFlatCharge(BigDecimal.valueOf(25));
        final OutstandingAmountsDTO outstanding = outstanding(500, 50);
        when(loanTransactionProcessingService.fetchPrepaymentDetail(scheduleGeneratorDTO, BEFORE_MATURITY, loan)).thenReturn(outstanding);

        underTest.applyPrepayChargeIfApplicable(loan, BEFORE_MATURITY, Money.of(currency, BigDecimal.valueOf(100)), scheduleGeneratorDTO);

        verify(loanChargeService, never()).addLoanCharge(any(), any());
    }

    @Test
    public void chargeIsNotAppliedWhenTheOutstandingAmountCannotBeEstablished() {
        givenFlatCharge(BigDecimal.valueOf(25));
        when(loan.isInterestBearingAndInterestRecalculationEnabled()).thenReturn(true);
        when(loanAccountDomainServiceJpaHelper.fetchPrepaymentDetailInIsolation(1L, BEFORE_MATURITY, scheduleGeneratorDTO))
                .thenReturn(null);

        underTest.applyPrepayChargeIfApplicable(loan, BEFORE_MATURITY, Money.of(currency, BigDecimal.valueOf(575)), scheduleGeneratorDTO);

        verify(loanChargeService, never()).addLoanCharge(any(), any());
    }

    private void givenFlatCharge(final BigDecimal amount) {
        when(prepayCharge.getChargeCalculation()).thenReturn(ChargeCalculationType.FLAT.getValue());
        when(prepayCharge.getChargeTimeType()).thenReturn(ChargeTimeType.PREPAY_LOAN.getValue());
        when(prepayCharge.getAmount()).thenReturn(amount);
    }

    private void givenPercentageCharge(final ChargeCalculationType calculationType, final BigDecimal percentage) {
        when(prepayCharge.getChargeCalculation()).thenReturn(calculationType.getValue());
        when(prepayCharge.getChargeTimeType()).thenReturn(ChargeTimeType.PREPAY_LOAN.getValue());
        when(prepayCharge.getAmount()).thenReturn(percentage);
    }

    private OutstandingAmountsDTO outstanding(final int principal, final int interest) {
        return new OutstandingAmountsDTO(currency) //
                .principal(Money.of(currency, BigDecimal.valueOf(principal))) //
                .interest(Money.of(currency, BigDecimal.valueOf(interest)));
    }
}
