@PETROCORE-61335
Feature: Charity Donations

  # PETROCORE-61335, "Charity Donations - Cash Department sale with Rest for charity" in PATS.
  # Rewritten 2026-10-07, step for step, from PETROCORE-61335-TestCase.py (hand-migrated from PATS);
  # its migration notes stay in that file's git history.
  Scenario: Cash department sale with rest for charity
    Given the register is idle
    When the cashier keys 1 at 10.77 on department key 0x0c for department 1567
    And the cashier keys TOTAL
    And the cashier keys 11.00 on the CASH key
    Then the pinpad shows "donate"
    When the customer presses "$0.23" on the pinpad
    Then the receipt matches its baseline
    And the transaction log matches its baseline
