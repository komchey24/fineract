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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mockStatic;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import org.apache.fineract.organisation.monetary.data.CurrencyData;
import org.apache.fineract.organisation.monetary.domain.Money;
import org.apache.fineract.organisation.monetary.domain.MoneyHelper;
import org.apache.fineract.portfolio.loanaccount.loanschedule.exception.LoanAdjustedInstallmentAmountTooLowException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Pins the arithmetic of the manual installment amount adjustment: interest is back-solved from the officer supplied
 * installment total, and the loan level total interest follows from it as {@code T * n - P}.
 * <p>
 * Figures are the KHR case that motivated the feature: a 400,000 loan over 4 installments whose nominal flat interest
 * of 22,600 leaves an uncollectible 50 KHR tail on each installment.
 */
class LoanApplicationTermsInstallmentAdjustmentTest {

    private static final MathContext MATH_CONTEXT = new MathContext(19, RoundingMode.HALF_EVEN);
    // KHR has no minor unit, hence zero decimal places.
    private static final CurrencyData KHR = new CurrencyData("KHR", 0, null);

    private static final BigDecimal PRINCIPAL = BigDecimal.valueOf(400000);
    private static final BigDecimal PRINCIPAL_PER_INSTALLMENT = BigDecimal.valueOf(100000);
    private static final BigDecimal TARGET_INSTALLMENT = BigDecimal.valueOf(105700);
    private static final int NUMBER_OF_REPAYMENTS = 4;

    private static final MockedStaticHolder MONEY_HELPER = new MockedStaticHolder();

    /** Holder so the static mock can be opened once and closed once without leaking between test classes. */
    private static final class MockedStaticHolder {

        private final org.mockito.MockedStatic<MoneyHelper> delegate = mockStatic(MoneyHelper.class);
    }

    @BeforeAll
    static void init() {
        MONEY_HELPER.delegate.when(MoneyHelper::getRoundingMode).thenReturn(RoundingMode.HALF_EVEN);
        MONEY_HELPER.delegate.when(MoneyHelper::getMathContext).thenReturn(MATH_CONTEXT);
    }

    @AfterAll
    static void tearDown() {
        MONEY_HELPER.delegate.close();
    }

    @Test
    void interestIsBackSolvedFromTheInstallmentTotal() {
        final LoanApplicationTerms terms = terms(TARGET_INSTALLMENT);

        final Money interest = terms.calculateAdjustedInterestForPeriod(Money.of(KHR, PRINCIPAL_PER_INSTALLMENT));

        // 105,700 - 100,000 = 5,700, up from the nominal 5,650.
        assertEquals(0, BigDecimal.valueOf(5700).compareTo(interest.getAmount()));
    }

    @Test
    void totalInterestIsInstallmentAmountTimesTermMinusPrincipal() {
        final LoanApplicationTerms terms = terms(TARGET_INSTALLMENT);

        final Money totalInterest = terms.calculateAdjustedTotalInterestCharged();

        // 105,700 x 4 - 400,000 = 22,800, i.e. 200 more than the nominal 22,600.
        assertEquals(0, BigDecimal.valueOf(22800).compareTo(totalInterest.getAmount()));
    }

    @Test
    void everyInstallmentTotalEqualsTheRequestedAmount() {
        final LoanApplicationTerms terms = terms(TARGET_INSTALLMENT);

        for (int period = 1; period <= NUMBER_OF_REPAYMENTS; period++) {
            final Money principal = Money.of(KHR, PRINCIPAL_PER_INSTALLMENT);
            final Money interest = terms.calculateAdjustedInterestForPeriod(principal);
            assertEquals(0, TARGET_INSTALLMENT.compareTo(principal.plus(interest).getAmount()),
                    "installment " + period + " total should equal the requested amount");
        }
    }

    @Test
    void installmentAmountBelowThePrincipalPortionIsRejected() {
        final LoanApplicationTerms terms = terms(BigDecimal.valueOf(99000));

        assertThrows(LoanAdjustedInstallmentAmountTooLowException.class,
                () -> terms.calculateAdjustedInterestForPeriod(Money.of(KHR, PRINCIPAL_PER_INSTALLMENT)));
    }

    @Test
    void adjustmentIsInactiveWhenUnsetOrZero() {
        assertFalse(terms(null).isInstallmentAmountAdjusted());
        assertFalse(terms(BigDecimal.ZERO).isInstallmentAmountAdjusted());
        assertTrue(terms(TARGET_INSTALLMENT).isInstallmentAmountAdjusted());
    }

    private LoanApplicationTerms terms(final BigDecimal adjustedInstallmentAmount) {
        final LoanApplicationTerms terms = new LoanApplicationTerms.Builder().currency(KHR).principal(Money.of(KHR, PRINCIPAL))
                .numberOfRepayments(NUMBER_OF_REPAYMENTS).build();
        terms.setAdjustedInstallmentAmount(adjustedInstallmentAmount);
        // actualNumberOfRepayments is only derived inside assembleFrom, which needs a full loan graph; set it directly
        // so the total interest calculation has the term to multiply by.
        setField(terms, "actualNumberOfRepayments", NUMBER_OF_REPAYMENTS);
        return terms;
    }

    private static void setField(final Object target, final String name, final Object value) {
        try {
            final Field field = LoanApplicationTerms.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new IllegalStateException("Unable to set " + name, e);
        }
    }
}
