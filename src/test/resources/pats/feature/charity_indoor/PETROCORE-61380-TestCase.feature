@PETROCORE-61380
Feature: Charity Donations

  # PETROCORE-61380, "Charity Donations - Cash Prepay Merchandise" in PATS.
  # Rewritten 2026-10-07, step for step, from PETROCORE-61380-TestCase.py (hand-migrated from PATS);
  # its migration notes stay in that file's git history.
  Scenario: Cash prepay merchandise
    Given the register is idle
    When the cashier keys PLU 32232211
    And the cashier keys a 5.00 prepay on pump 1 with the CREDIT key
    And the cashier keys TOTAL
    And the cashier keys 14.50 on the CASH key
    Then the receipt matches its baseline
    And the transaction log matches its baseline
    When pump 1 dispenses 5.00 of its prepay
    And the customer hangs up prepaid pump 1
