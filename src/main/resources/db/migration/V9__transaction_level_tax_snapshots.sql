ALTER TABLE sales ADD COLUMN taxable_base_paise INTEGER NOT NULL DEFAULT 0 CHECK(taxable_base_paise >= 0);
ALTER TABLE sales ADD COLUMN tax_rate_bps INTEGER NOT NULL DEFAULT 0 CHECK(tax_rate_bps BETWEEN 0 AND 10000);
ALTER TABLE sales ADD COLUMN tax_label_snapshot TEXT NOT NULL DEFAULT 'GST';
ALTER TABLE sales ADD COLUMN tax_amount_paise INTEGER NOT NULL DEFAULT 0 CHECK(tax_amount_paise >= 0);
ALTER TABLE sales ADD COLUMN cgst_paise INTEGER NOT NULL DEFAULT 0 CHECK(cgst_paise >= 0);
ALTER TABLE sales ADD COLUMN sgst_paise INTEGER NOT NULL DEFAULT 0 CHECK(sgst_paise >= 0);
ALTER TABLE sales ADD COLUMN igst_paise INTEGER NOT NULL DEFAULT 0 CHECK(igst_paise >= 0);
ALTER TABLE sales ADD COLUMN seller_state_code_snapshot TEXT NOT NULL DEFAULT '';
ALTER TABLE sales ADD COLUMN buyer_state_code_snapshot TEXT NOT NULL DEFAULT '';
ALTER TABLE sales ADD COLUMN tax_classification TEXT NOT NULL DEFAULT 'NONE';
-- Existing sales are intentionally left with zero tax snapshots; historical totals are not recomputed.
