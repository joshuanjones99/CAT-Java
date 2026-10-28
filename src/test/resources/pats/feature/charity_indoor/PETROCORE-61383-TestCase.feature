@PETROCORE-61383
Feature: Charity Donations

  # PETROCORE-61383, "Charity Donations - Cash Prepay" in PATS.
  # Rewritten 2026-10-07, step for step, from PETROCORE-61383-TestCase.py (hand-migrated from PATS);
  # its migration notes stay in that file's git history.
  Scenario: Cash prepay with donation
    Given the register is idle
    When the cashier keys a 5.00 prepay on pump 1 with the CREDIT key
    And the cashier keys TOTAL
    And the cashier keys 6.00 on the CASH key
    Then the pinpad shows "donate"
    When the customer presses "$1.00" on the pinpad
    Then the receipt matches its baseline
    And the transaction log matches its baseline
    When pump 1 dispenses 5.00 of its prepay
    And the customer hangs up prepaid pump 1
