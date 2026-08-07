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
package org.apache.fineract.portfolio.loanaccount.serialization;

import com.google.gson.JsonElement;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.apache.fineract.infrastructure.core.api.JsonCommand;
import org.apache.fineract.infrastructure.core.exception.InvalidJsonException;
import org.apache.fineract.infrastructure.core.serialization.FromJsonHelper;
import org.apache.fineract.infrastructure.core.service.MathUtil;
import org.apache.fineract.portfolio.common.service.Validator;
import org.apache.fineract.portfolio.loanaccount.api.LoanApiConstants;
import org.apache.fineract.portfolio.loanaccount.domain.Loan;
import org.apache.fineract.portfolio.loanaccount.domain.LoanRepository;
import org.apache.fineract.portfolio.loanaccount.domain.LoanStatus;
import org.apache.fineract.portfolio.loanaccount.exception.LoanNotFoundException;
import org.apache.fineract.portfolio.loanproduct.domain.LoanProductRelatedDetail;
import org.springframework.stereotype.Component;

/**
 * Validates the manual installment amount adjustment. The feature back-solves interest from an officer supplied
 * installment total, which is only well defined for a plain FLAT loan that has not been disbursed yet, so everything
 * outside that shape is rejected explicitly rather than silently producing a schedule that does not honour the
 * requested amount.
 */
@Component
@RequiredArgsConstructor
public final class LoanInstallmentAmountAdjustmentValidatorImpl implements LoanInstallmentAmountAdjustmentValidator {

    private static final Set<LoanStatus> VALID_LOAN_STATUSES_FOR_INSTALLMENT_AMOUNT_ADJUSTMENT = Set
            .of(LoanStatus.SUBMITTED_AND_PENDING_APPROVAL, LoanStatus.APPROVED);

    private final FromJsonHelper fromApiJsonHelper;
    private final LoanRepository loanRepository;

    @Override
    public void validateInstallmentAmountAdjustment(final JsonCommand command) {
        final String json = command.json();
        if (StringUtils.isBlank(json)) {
            throw new InvalidJsonException();
        }

        final Set<String> supportedParameters = new HashSet<>(
                Arrays.asList(LoanApiConstants.installmentAmountParameterName, LoanApiConstants.localeParameterName));

        final JsonElement element = this.fromApiJsonHelper.parse(json);
        final Type typeOfMap = new TypeToken<Map<String, Object>>() {}.getType();
        this.fromApiJsonHelper.checkForUnsupportedParameters(typeOfMap, json, supportedParameters);

        final BigDecimal installmentAmount = this.fromApiJsonHelper
                .extractBigDecimalWithLocaleNamed(LoanApiConstants.installmentAmountParameterName, element);

        Validator.validateOrThrow("loan.installment.amount", baseDataValidator -> {
            baseDataValidator.reset().parameter(LoanApiConstants.installmentAmountParameterName).value(installmentAmount).notNull();
        });

        Validator.validateOrThrowDomainViolation("loan.installment.amount", baseDataValidator -> {
            baseDataValidator.reset().parameter(LoanApiConstants.installmentAmountParameterName).value(installmentAmount).positiveAmount();

            final Long loanId = command.getLoanId();
            final Loan loan = this.loanRepository.findById(loanId).orElseThrow(() -> new LoanNotFoundException(loanId));
            final LoanProductRelatedDetail loanProductRelatedDetail = loan.getLoanProductRelatedDetail();

            if (!VALID_LOAN_STATUSES_FOR_INSTALLMENT_AMOUNT_ADJUSTMENT.contains(loan.getStatus())) {
                baseDataValidator.reset()
                        .failWithCodeNoParameterAddedToErrorCode("loan.status.not.valid.for.installment.amount.adjustment");
            }
            if (!loanProductRelatedDetail.getInterestMethod().isFlat() && !loanProductRelatedDetail.isEqualAmortization()) {
                baseDataValidator.reset().failWithCodeNoParameterAddedToErrorCode("only.supported.for.flat.interest.loans");
            }
            if (loan.isProgressiveSchedule()) {
                baseDataValidator.reset().failWithCodeNoParameterAddedToErrorCode("not.supported.for.progressive.loans");
            }
            if (loan.loanProduct().isMultiDisburseLoan()) {
                baseDataValidator.reset().failWithCodeNoParameterAddedToErrorCode("not.supported.with.multiple.disbursements");
            }
            if (loanProductRelatedDetail.isEnableDownPayment()) {
                baseDataValidator.reset().failWithCodeNoParameterAddedToErrorCode("not.supported.with.down.payment");
            }
            if (loanProductRelatedDetail.isInterestRecalculationEnabled()) {
                baseDataValidator.reset().failWithCodeNoParameterAddedToErrorCode("not.supported.with.interest.recalculation");
            }
            if (isPositive(loanProductRelatedDetail.getGraceOnInterestPayment())
                    || isPositive(loanProductRelatedDetail.getGraceOnInterestCharged())) {
                // The adjustment bypasses the grace aware interest calculation entirely, so grace would be silently
                // ignored rather than applied. Failing loudly is the lesser evil.
                baseDataValidator.reset().failWithCodeNoParameterAddedToErrorCode("not.supported.with.interest.grace");
            }
            if (loan.getFixedEmiAmount() != null) {
                baseDataValidator.reset().failWithCodeNoParameterAddedToErrorCode("not.allowed.with.fixed.emi.amount");
            }

            // Cheap lower bound so the obvious mistake is caught before the schedule is regenerated. The generator
            // itself is the authoritative guard: it throws per period if interest would go negative.
            final Integer numberOfRepayments = loanProductRelatedDetail.getNumberOfRepayments();
            if (loan.getApprovedPrincipal() != null && isPositive(numberOfRepayments)) {
                final BigDecimal minimumInstallmentAmount = loan.getApprovedPrincipal().divide(BigDecimal.valueOf(numberOfRepayments), 0,
                        RoundingMode.UP);
                if (MathUtil.isLessThan(installmentAmount, minimumInstallmentAmount)) {
                    baseDataValidator.reset().parameter(LoanApiConstants.installmentAmountParameterName)
                            .failWithCode("less.than.principal.per.installment");
                }
            }
        });
    }

    private boolean isPositive(final Integer value) {
        return value != null && value > 0;
    }
}
