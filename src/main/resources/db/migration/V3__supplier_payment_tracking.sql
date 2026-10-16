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

-- Payment entries are immutable evidence: only a one-way void with a reason is allowed.
CREATE TRIGGER IF NOT EXISTS trg_supplier_payment_audit_immutable
BEFORE UPDATE ON supplier_payments
WHEN NOT (
  OLD.status='POSTED' AND NEW.status='VOIDED'
  AND NEW.purchase_id=OLD.purchase_id
  AND NEW.method=OLD.method
  AND NEW.amount_paise=OLD.amount_paise
  AND COALESCE(NEW.reference,'')=COALESCE(OLD.reference,'')
  AND NEW.created_at=OLD.created_at
  AND NEW.void_reason IS NOT NULL AND length(trim(NEW.void_reason))>0
  AND NEW.voided_at IS NOT NULL
)
BEGIN
  SELECT RAISE(ABORT, 'supplier payment records are immutable; only a void with reason is allowed');
END;

CREATE TRIGGER IF NOT EXISTS trg_supplier_payment_no_delete
BEFORE DELETE ON supplier_payments
BEGIN
  SELECT RAISE(ABORT, 'supplier payment audit records cannot be deleted');
END;
