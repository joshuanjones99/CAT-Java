@PETROCORE-61369
Feature: Charity Donations

  # PETROCORE-61369, "Charity Donations- Basic+Code - Qualify by PLU" in PATS.
  # Rewritten 2026-10-07, step for step, from PETROCORE-61369-TestCase.py (hand-migrated from PATS);
  # its migration notes stay in that file's git history.
  Scenario: Fuel and cash alternative by plu
    Given the register is idle
    When pump 1 dispenses 24.15
    And the customer hangs up pump 1
    And the cashier claims the fuel sale on pump 1
    And the cashier keys TOTAL
    And the cashier keys 25.00 on the CASH key
    Then the pinpad shows "donate"
    When the customer presses "$3.00" on the pinpad
    Then the receipt matches its baseline
    And the transaction log matches its baseline
