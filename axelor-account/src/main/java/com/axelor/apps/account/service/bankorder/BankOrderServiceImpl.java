/**
 * Axelor Business Solutions
 *
 * Copyright (C) 2016 Axelor (<http://axelor.com>).
 *
 * This program is free software: you can redistribute it and/or  modify
 * it under the terms of the GNU Affero General Public License, version 3,
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.axelor.apps.account.service.bankorder;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.List;

import javax.xml.bind.JAXBException;
import javax.xml.datatype.DatatypeConfigurationException;

import java.time.LocalDate;
import java.time.LocalDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.axelor.apps.account.db.BankOrder;
import com.axelor.apps.account.db.BankOrderFileFormat;
import com.axelor.apps.account.db.BankOrderLine;
import com.axelor.apps.account.db.InvoicePayment;
import com.axelor.apps.account.db.PaymentMode;
import com.axelor.apps.account.db.repo.BankOrderFileFormatRepository;
import com.axelor.apps.account.db.repo.BankOrderRepository;
import com.axelor.apps.account.db.repo.InvoicePaymentRepository;
import com.axelor.apps.account.ebics.service.EbicsService;
import com.axelor.apps.account.exception.IExceptionMessage;
import com.axelor.apps.account.service.bankorder.file.transfer.BankOrderFile00100102Service;
import com.axelor.apps.account.service.bankorder.file.transfer.BankOrderFile00100103Service;
import com.axelor.apps.account.service.bankorder.file.transfer.BankOrderFileAFB320Service;
import com.axelor.apps.tool.StringTool;
import com.axelor.exception.AxelorException;
import com.axelor.exception.db.IException;
import com.axelor.i18n.I18n;
import com.axelor.inject.Beans;
import com.axelor.meta.MetaFiles;
import com.google.inject.Inject;
import com.google.inject.persist.Transactional;

public class BankOrderServiceImpl implements BankOrderService  {
	
	private final Logger log = LoggerFactory.getLogger( getClass() );
	
	protected BankOrderRepository bankOrderRepo;
	protected InvoicePaymentRepository invoicePaymentRepo;
	protected BankOrderLineService bankOrderLineService;
	protected EbicsService ebicsService;

	
	@Inject
	public BankOrderServiceImpl(BankOrderRepository bankOrderRepo, InvoicePaymentRepository invoicePaymentRepo, 
			BankOrderLineService bankOrderLineService, EbicsService ebicsService)  {
		
		this.bankOrderRepo = bankOrderRepo;
		this.invoicePaymentRepo = invoicePaymentRepo;
		this.bankOrderLineService = bankOrderLineService;
		this.ebicsService = ebicsService;
		
	}
	
	
	public void checkPreconditions(BankOrder bankOrder) throws AxelorException  {
		
		LocalDate brankOrderDate = bankOrder.getBankOrderDate();
		Integer orderType = bankOrder.getOrderTypeSelect();
		Integer partnerType = bankOrder.getPartnerTypeSelect();
		
		if (brankOrderDate != null)  {
			if(brankOrderDate.isBefore(LocalDate.now()))  {
				throw new AxelorException(I18n.get(IExceptionMessage.BANK_ORDER_DATE), IException.INCONSISTENCY);
			}
		}else{
			throw new AxelorException(I18n.get(IExceptionMessage.BANK_ORDER_DATE_MISSING), IException.INCONSISTENCY);
		}
		
		if(orderType == 0)  {
			throw new AxelorException(I18n.get(IExceptionMessage.BANK_ORDER_TYPE_MISSING), IException.INCONSISTENCY);
		}
		else{
			if(orderType !=  BankOrderRepository.BANK_TO_BANK_TRANSFER  && partnerType == 0)  {
				throw new AxelorException(I18n.get(IExceptionMessage.BANK_ORDER_PARTNER_TYPE_MISSING), IException.INCONSISTENCY);
			}
		}
		if(bankOrder.getPaymentMode() == null)  {
			throw new AxelorException(I18n.get(IExceptionMessage.BANK_ORDER_PAYMENT_MODE_MISSING), IException.INCONSISTENCY);
		}
		if(bankOrder.getSenderCompany() == null)  {
			throw new AxelorException(I18n.get(IExceptionMessage.BANK_ORDER_COMPANY_MISSING), IException.INCONSISTENCY);
		}
		if(bankOrder.getSenderBankDetails() == null)  {
			throw new AxelorException(I18n.get(IExceptionMessage.BANK_ORDER_BANK_DETAILS_MISSING), IException.INCONSISTENCY);
		}
		if(!bankOrder.getIsMultiCurrency() && bankOrder.getBankOrderCurrency() == null)  {
			throw new AxelorException(I18n.get(IExceptionMessage.BANK_ORDER_CURRENCY_MISSING), IException.INCONSISTENCY);
		}
		if(bankOrder.getSignatoryUser() == null)  {
			throw new AxelorException(I18n.get(IExceptionMessage.BANK_ORDER_SIGNATORY_MISSING), IException.INCONSISTENCY);
		}
		
	}
	
	
	@Override
	public BigDecimal computeBankOrderTotalAmount(BankOrder bankOrder) throws AxelorException  {

		BigDecimal bankOrderTotalAmount = BigDecimal.ZERO;
		
		List<BankOrderLine> bankOrderLines = bankOrder.getBankOrderLineList();
		if(bankOrderLines != null){
			for (BankOrderLine bankOrderLine : bankOrderLines) {
				BigDecimal  amount = bankOrderLine.getBankOrderAmount();
				if(amount != null)  {
					bankOrderTotalAmount = bankOrderTotalAmount.add(amount);
				}
					
			}
		}
		return bankOrderTotalAmount;
	}
	
	@Override
	public BigDecimal computeCompanyCurrencyTotalAmount(BankOrder bankOrder) throws AxelorException  {

		BigDecimal  companyCurrencyTotalAmount = BigDecimal.ZERO;
		
		List<BankOrderLine> bankOrderLines = bankOrder.getBankOrderLineList();
		if(bankOrderLines != null){
			for (BankOrderLine bankOrderLine : bankOrderLines) {
				BigDecimal  amount = bankOrderLine.getCompanyCurrencyAmount();
				if(amount != null)  {
					companyCurrencyTotalAmount = companyCurrencyTotalAmount.add(amount);
				}
					
			}
		}
		return companyCurrencyTotalAmount;
	}
	
	
	public void updateTotalAmounts(BankOrder bankOrder) throws AxelorException  {
		bankOrder.setArithmeticTotal(this.computeBankOrderTotalAmount(bankOrder));
		
		if(!bankOrder.getIsMultiCurrency())  {
			bankOrder.setBankOrderTotalAmount(bankOrder.getArithmeticTotal());
		}
		
		bankOrder.setCompanyCurrencyTotalAmount(this.computeCompanyCurrencyTotalAmount(bankOrder));
	}
	
	
	@Override
	@Transactional(rollbackOn = {AxelorException.class, Exception.class})
	public BankOrder generateSequence(BankOrder bankOrder) {
		if(bankOrder.getBankOrderSeq() == null && bankOrder.getId() != null){
			bankOrder.setBankOrderSeq("*" + StringTool.fillStringLeft(Long.toString(bankOrder.getId()), '0', 6));
			bankOrderRepo.save(bankOrder);
		}
		return bankOrder;
	}
	
	@Override
	public void checkLines(BankOrder bankOrder) throws AxelorException {
		List<BankOrderLine> bankOrderLines = bankOrder.getBankOrderLineList();
		if(bankOrderLines.isEmpty()){
			throw new AxelorException(I18n.get(IExceptionMessage.BANK_ORDER_LINES_MISSING), IException.INCONSISTENCY);
		}
		else{
			validateBankOrderLines(bankOrderLines, bankOrder.getOrderTypeSelect(), bankOrder.getArithmeticTotal());
		}
	}

	
	public void validateBankOrderLines(List<BankOrderLine> bankOrderLines, int orderType, BigDecimal arithmeticTotal)  throws AxelorException{
		BigDecimal  totalAmount = BigDecimal.ZERO;
		for (BankOrderLine bankOrderLine : bankOrderLines) {
			
			bankOrderLineService.checkPreconditions(bankOrderLine, orderType);
			totalAmount = totalAmount.add(bankOrderLine.getBankOrderAmount());
			
		}
		if (!totalAmount.equals(arithmeticTotal))  {
			throw new AxelorException(I18n.get(IExceptionMessage.BANK_ORDER_LINE_TOTAL_AMOUNT_INVALID), IException.INCONSISTENCY);
		}
	}

	
	@Override
	@Transactional(rollbackOn = {AxelorException.class, Exception.class})
	public void validatePayment(BankOrder bankOrder) {
		
		InvoicePayment invoicePayment = invoicePaymentRepo.findByBankOrder(bankOrder).fetchOne();
		if(invoicePayment != null)  {
			invoicePayment.setStatusSelect(InvoicePaymentRepository.STATUS_VALIDATED);
		}
	}

	@Override
	@Transactional(rollbackOn = {AxelorException.class, Exception.class})
	public void cancelPayment(BankOrder bankOrder) {
		InvoicePayment invoicePayment = invoicePaymentRepo.findByBankOrder(bankOrder).fetchOne();
		if(invoicePayment != null)  {
			invoicePayment.setStatusSelect(InvoicePaymentRepository.STATUS_CANCELED);
		}
		
	}
	
	@Override
	@Transactional(rollbackOn = {AxelorException.class, Exception.class})
	public void confirm(BankOrder bankOrder) {
		
		if (bankOrder.getOrderTypeSelect() == BankOrderRepository.SEPA_CREDIT_TRANSFER || bankOrder.getOrderTypeSelect() == BankOrderRepository.INTERNATIONAL_CREDIT_TRANSFER){
			bankOrder.setStatusSelect(BankOrderRepository.STATUS_AWAITING_SIGNATURE);
		}
		else{
			bankOrder.setStatusSelect(BankOrderRepository.STATUS_VALIDATED);
		}
		
		bankOrderRepo.save(bankOrder);
	}

	@Override
	@Transactional(rollbackOn = {AxelorException.class, Exception.class})
	public void sign(BankOrder bankOrder) {

	//TODO
		
	}
	
	@Transactional(rollbackOn = {AxelorException.class, Exception.class})
	public void validate(BankOrder bankOrder) throws JAXBException, IOException, AxelorException, DatatypeConfigurationException {
		
		bankOrder.setStatusSelect(BankOrderRepository.STATUS_VALIDATED);
		bankOrder.setValidationDateTime(LocalDateTime.now());
		
		this.setSequenceOnBankOrderLines(bankOrder);
		
		this.setNbOfLines(bankOrder);
		
		File fileToSend = this.generateFile(bankOrder);
		
		ebicsService.sendFULRequest(bankOrder.getEbicsUser(), null, fileToSend, bankOrder.getBankOrderFileFormat().getOrderFileFormatSelect());
		
		bankOrderRepo.save(bankOrder);

	}
	
	
	private void setSequenceOnBankOrderLines(BankOrder bankOrder)  {
		
		if(bankOrder.getBankOrderLineList() == null)  {  return;  }
		
		String bankOrderSeq = bankOrder.getBankOrderSeq();
		
		int counter = 1;
		
		for(BankOrderLine bankOrderLine : bankOrder.getBankOrderLineList())  {
			
			bankOrderLine.setCounter(counter);
			bankOrderLine.setSequence(bankOrderSeq + "-" + Integer.toString(counter++));
		}
		
	}
	
	private void setNbOfLines(BankOrder bankOrder)  {
		
		if(bankOrder.getBankOrderLineList() == null)  {  return;  }
		
		bankOrder.setNbOfLines(bankOrder.getBankOrderLineList().size());
		
	}
	

	@Override
	@Transactional(rollbackOn = {AxelorException.class, Exception.class})
	public void cancelBankOrder(BankOrder bankOrder) {
		bankOrder.setStatusSelect(BankOrderRepository.STATUS_CANCELED);
		bankOrderRepo.save(bankOrder);
		
	}
	
	
	public File generateFile(BankOrder bankOrder) throws JAXBException, IOException, AxelorException, DatatypeConfigurationException  {
		
		bankOrder.setFileGenerationDateTime(LocalDateTime.now());
		
		BankOrderFileFormat bankOrderFileFormat = bankOrder.getBankOrderFileFormat();
		
		File file = null;
		
		switch (bankOrderFileFormat.getOrderFileFormatSelect()) {
		case BankOrderFileFormatRepository.FILE_FORMAT_pain_001_001_02_SCT :
			
			file = new BankOrderFile00100102Service(bankOrder).generateFile();
			break;
			
		case BankOrderFileFormatRepository.FILE_FORMAT_pain_001_001_03_SCT :
			
			file = new BankOrderFile00100103Service(bankOrder).generateFile();
			break;
			
		case BankOrderFileFormatRepository.FILE_FORMAT_pain_XXX_CFONB320_XCT :
			
			file = new BankOrderFileAFB320Service(bankOrder).generateFile();
			break;

		default:
			
			throw new AxelorException(I18n.get(IExceptionMessage.BANK_ORDER_FILE_UNKNOW_FORMAT), IException.INCONSISTENCY);
		}
		
		if(file == null)  {
			throw new AxelorException(I18n.get(String.format(IExceptionMessage.BANK_ORDER_ISSUE_DURING_FILE_GENERATION, bankOrder.getBankOrderSeq())), IException.INCONSISTENCY);
		}	
		
		MetaFiles metaFiles = Beans.get(MetaFiles.class);
		
		try (InputStream is = new FileInputStream(file)) {
			metaFiles.attach(is, file.getName(), bankOrder);
			bankOrder.setFileToSend(metaFiles.upload(file));
		}			

		return file;
	}
	
	
	
	
}


























