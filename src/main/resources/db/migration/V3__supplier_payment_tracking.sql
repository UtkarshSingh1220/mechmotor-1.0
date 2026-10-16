CREATE TABLE IF NOT EXISTS supplier_payments (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  purchase_id INTEGER NOT NULL REFERENCES purchases(id),
  method TEXT NOT NULL CHECK(method IN ('CASH','UPI','CARD','BANK_TRANSFER','CHEQUE','OTHER')),
  amount_paise INTEGER NOT NULL CHECK(amount_paise > 0),
  reference TEXT,
  status TEXT NOT NULL DEFAULT 'POSTED' CHECK(status IN ('POSTED','VOIDED')),
  void_reason TEXT,
  created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  voided_at TEXT,
  CHECK((status='POSTED' AND void_reason IS NULL AND voided_at IS NULL)
     OR (status='VOIDED' AND length(trim(void_reason))>0 AND voided_at IS NOT NULL))
);
CREATE INDEX IF NOT EXISTS idx_supplier_payments_purchase_status
  ON supplier_payments(purchase_id,status,id);
CREATE UNIQUE INDEX IF NOT EXISTS idx_supplier_payment_reference_active
  ON supplier_payments(purchase_id,method,reference)
  WHERE status='POSTED' AND reference IS NOT NULL AND trim(reference)<>'';

-- Enforce the receipt's remaining payable at the database boundary as well as
-- in the service layer, including writes from a second app instance or scripts.
CREATE TRIGGER IF NOT EXISTS trg_supplier_payment_no_overpay
BEFORE INSERT ON supplier_payments
WHEN NEW.status='POSTED'
BEGIN
  SELECT CASE
    WHEN COALESCE((SELECT status FROM purchases WHERE id=NEW.purchase_id),'') <> 'RECEIVED'
    THEN RAISE(ABORT, 'supplier payment requires a received purchase')
  END;
  SELECT CASE
    WHEN NEW.amount_paise > (
      COALESCE((SELECT subtotal_paise FROM purchases WHERE id=NEW.purchase_id),0)
      - COALESCE((SELECT SUM(amount_paise) FROM supplier_payments
                  WHERE purchase_id=NEW.purchase_id AND status='POSTED'),0)
    )
    THEN RAISE(ABORT, 'supplier payment exceeds outstanding balance')
  END;
END;
