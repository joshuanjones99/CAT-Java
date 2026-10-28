@PETROCORE-61372
Feature: Charity Donations

  # PETROCORE-61372, "Charity Donations_Rest for charity_ Pay Inside" in PATS.
  # Rewritten 2026-10-07, step for step, from PETROCORE-61372-TestCase.py (hand-migrated from PATS);
  # its migration notes stay in that file's git history.
  Scenario: Fuel cash pay inside rest for charity
    Given the register is idle
    Then the DCR shows "WELCOME"
    When the customer presses L3 on the DCR
    Then the DCR shows "REMOVE NOZZLE"
    When pump 1 dispenses 21.23
    And the customer hangs up pump 1
    And the cashier claims the fuel sale on pump 1
    And the cashier keys TOTAL
    And the cashier keys the CASH key
    And the cashier enters "2000"
    Then the pinpad shows "donate"
    When the customer presses "$0.66" on the pinpad
    Then the receipt matches its baseline
    And the transaction log matches its baseline
