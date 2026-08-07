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
package org.apache.fineract.portfolio.loanaccount.loanschedule.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.infrastructure.core.api.JsonCommand;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResult;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResultBuilder;
import org.apache.fineract.infrastructure.event.business.domain.loan.LoanScheduleVariationsAddedBusinessEvent;
import org.apache.fineract.infrastructure.event.business.domain.loan.LoanScheduleVariationsDeletedBusinessEvent;
import org.apache.fineract.infrastructure.event.business.service.BusinessEventNotifierService;
import org.apache.fineract.portfolio.loanaccount.api.LoanApiConstants;
import org.apache.fineract.portfolio.loanaccount.data.LoanTermVariationsData;
import org.apache.fineract.portfolio.loanaccount.data.ScheduleGeneratorDTO;
import org.apache.fineract.portfolio.loanaccount.domain.Loan;
import org.apache.fineract.portfolio.loanaccount.domain.LoanAccountService;
import org.apache.fineract.portfolio.loanaccount.domain.LoanTermVariations;
import org.apache.fineract.portfolio.loanaccount.serialization.LoanInstallmentAmountAdjustmentValidator;
import org.apache.fineract.portfolio.loanaccount.service.LoanAccrualsProcessingService;
import org.apache.fineract.portfolio.loanaccount.service.LoanAssembler;
import org.apache.fineract.portfolio.loanaccount.service.LoanScheduleService;
import org.apache.fineract.portfolio.loanaccount.service.LoanUtilService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
@RequiredArgsConstructor
public class LoanScheduleWritePlatformServiceImpl implements LoanScheduleWritePlatformService {

    private final LoanAssembler loanAssembler;
    private final LoanScheduleAssembler loanScheduleAssembler;
    private final LoanUtilService loanUtilService;
    private final BusinessEventNotifierService businessEventNotifierService;
    private final LoanAccrualsProcessingService loanAccrualsProcessingService;
    private final LoanScheduleService loanScheduleService;
    private final LoanAccountService loanAccountService;
    private final LoanInstallmentAmountAdjustmentValidator loanInstallmentAmountAdjustmentValidator;

    @Override
    public CommandProcessingResult addLoanScheduleVariations(final Long loanId, final JsonCommand command) {
        final Loan loan = loanAssembler.assembleFrom(loanId);
        Map<Long, LoanTermVariations> loanTermVariations = new HashMap<>();
        for (LoanTermVariations termVariations : loan.getLoanTermVariations()) {
            loanTermVariations.put(termVariations.getId(), termVariations);
        }
        loanScheduleAssembler.assempleVariableScheduleFrom(loan, command.json());

        loanAccountService.saveLoanWithDataIntegrityViolationChecks(loan);
        final Map<String, Object> changes = new HashMap<>();
        List<LoanTermVariationsData> newVariationsData = new ArrayList<>();
        List<LoanTermVariations> modifiedVariations = loan.getLoanTermVariations();
        for (LoanTermVariations termVariations : modifiedVariations) {
            if (loanTermVariations.containsKey(termVariations.getId())) {
                loanTermVariations.remove(termVariations.getId());
            } else {
                newVariationsData.add(termVariations.toData());
            }
        }
        if (!loanTermVariations.isEmpty()) {
            changes.put("removedVariations", loanTermVariations.keySet());
        }
        changes.put("loanTermVariations", newVariationsData);
        businessEventNotifierService.notifyPostBusinessEvent(new LoanScheduleVariationsAddedBusinessEvent(loan));
        return new CommandProcessingResultBuilder() //
                .withCommandId(command.commandId()) //
                .withLoanId(loanId) //
                .with(changes) //
                .build();
    }

    @Override
    public CommandProcessingResult deleteLoanScheduleVariations(final Long loanId) {
        final Loan loan = loanAssembler.assembleFrom(loanId);
        List<LoanTermVariations> variations = loan.getLoanTermVariations();
        List<Long> deletedVariations = new ArrayList<>(variations.size());
        for (LoanTermVariations loanTermVariations : variations) {
            deletedVariations.add(loanTermVariations.getId());
        }
        final Map<String, Object> changes = new HashMap<>();
        changes.put("removedEntityIds", deletedVariations);
        loan.getLoanTermVariations().clear();
        final LocalDate recalculateFrom = null;
        ScheduleGeneratorDTO scheduleGeneratorDTO = loanUtilService.buildScheduleGeneratorDTO(loan, recalculateFrom);
        loanScheduleService.regenerateRepaymentSchedule(loan, scheduleGeneratorDTO);
        loanAccrualsProcessingService.reprocessExistingAccruals(loan, false);
        loanAccountService.saveLoanWithDataIntegrityViolationChecks(loan);
        businessEventNotifierService.notifyPostBusinessEvent(new LoanScheduleVariationsDeletedBusinessEvent(loan));
        return new CommandProcessingResultBuilder() //
                .withLoanId(loanId) //
                .with(changes) //
                .build();
    }

    @Override
    public CommandProcessingResult adjustInstallmentAmount(final Long loanId, final JsonCommand command) {
        loanInstallmentAmountAdjustmentValidator.validateInstallmentAmountAdjustment(command);

        final Loan loan = loanAssembler.assembleFrom(loanId);
        final Map<String, Object> changes = new HashMap<>();
        changes.put("previousInstallmentAmount", loan.getAdjustedInstallmentAmount());

        final BigDecimal installmentAmount = command.bigDecimalValueOfParameterNamed(LoanApiConstants.installmentAmountParameterName);
        loan.setAdjustedInstallmentAmount(installmentAmount);
        changes.put(LoanApiConstants.installmentAmountParameterName, installmentAmount);
        changes.put(LoanApiConstants.localeParameterName, command.locale());

        // Regenerating inside the command transaction is what makes this safe: if any period would end up with a
        // negative interest portion the generator throws and the whole command - including the persisted amount -
        // rolls back.
        final LocalDate recalculateFrom = null;
        final ScheduleGeneratorDTO scheduleGeneratorDTO = loanUtilService.buildScheduleGeneratorDTO(loan, recalculateFrom);
        loanScheduleService.regenerateRepaymentSchedule(loan, scheduleGeneratorDTO);
        loanAccountService.saveLoanWithDataIntegrityViolationChecks(loan);

        return new CommandProcessingResultBuilder() //
                .withCommandId(command.commandId()) //
                .withEntityId(loan.getId()) //
                .withEntityExternalId(loan.getExternalId()) //
                .withOfficeId(loan.getOfficeId()) //
                .withClientId(loan.getClientId()) //
                .withGroupId(loan.getGroupId()) //
                .withLoanId(loanId) //
                .with(changes) //
                .build();
    }

}
