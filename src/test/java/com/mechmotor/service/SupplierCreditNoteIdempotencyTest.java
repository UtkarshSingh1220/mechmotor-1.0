package com.mechmotor.service;
import com.mechmotor.config.DatabaseMigrationRunner;
import com.mechmotor.domain.*;
import com.mechmotor.repository.PartRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.sqlite.SQLiteDataSource;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
class SupplierCreditBalanceTest {
 @TempDir Path dir;
 @Test void creditAfterFullPaymentMustNotCreateUnrepresentedNegativePayable() {
  var ds=new SQLiteDataSource(); ds.setUrl("jdbc:sqlite:"+dir.resolve("t.db"));
  new DatabaseMigrationRunner(ds).run(new DefaultApplicationArguments(new String[0]));
  var jdbc=new JdbcTemplate(ds); var parts=new PartRepository(jdbc);
  var service=new PurchasingService(jdbc,parts,new DataSourceTransactionManager(ds));
  long sup=parts.createSupplier(new SupplierDraft("Supplier","","SUP-CREDIT"));
  long part=parts.create(new PartDraft("CR-1",null,null,"Filter","B","C","Paper","White",null,sup,"10.00","25.00",3,1));
  var receipt=service.receive(new PurchaseDraft(part,2,1000,sup,"INV-CREDIT"));
  service.recordSupplierPayment(new SupplierPaymentDraft(receipt.purchaseNumber(),2000,SupplierPaymentDraft.Method.BANK_TRANSFER,"PAY-CREDIT"));
  long item=jdbc.queryForObject("SELECT id FROM purchase_items WHERE purchase_id=?",Long.class,receipt.purchaseId());
  assertThrows(IllegalArgumentException.class,()->service.recordSupplierReturn(new SupplierReturnDraft(receipt.purchaseNumber(),"Damaged","CN-CREDIT",List.of(new SupplierReturnLineDraft(item,1)))));
  assertEquals(0,service.balance(receipt.purchaseNumber()).outstandingPaise());
  assertEquals(5,parts.findById(part).quantity());
 }
}