@PETROCORE-61367
Feature: Charity Donations

  # PETROCORE-61367, "Charity Donations-NAXML.ComboMaintenance.ComboItems.Disc by amount" in PATS.
  # Rewritten 2026-10-07, step for step, from PETROCORE-61367-TestCase.py (hand-migrated from PATS);
  # its migration notes stay in that file's git history.
  Scenario: Cash sale with quantity 2
    Given the register is idle
    When the cashier keys 2 of PLU 7002
    And the cashier keys TOTAL
    And the cashier keys 18.00 on the CASH key
    Then the pinpad shows "donate"
    When the customer presses "$1.00" on the pinpad
    Then the receipt matches its baseline
    And the transaction log matches its baseline
