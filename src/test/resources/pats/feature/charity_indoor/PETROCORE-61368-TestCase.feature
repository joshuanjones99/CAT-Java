@PETROCORE-61368
Feature: Charity Donations

  # PETROCORE-61368, "Charity Donations- POP Discount Qualify By MOP" in PATS.
  # Rewritten 2026-10-07, step for step, from PETROCORE-61368-TestCase.py (hand-migrated from PATS);
  # its migration notes stay in that file's git history.
  Scenario: Fuel sale pop discount by mop
    Given the register is idle
    When pump 1 dispenses 24.15
    And the customer hangs up pump 1
    And the cashier claims the fuel sale on pump 1
    And the cashier keys TOTAL
    And the cashier keys the CREDIT key
    Then the pinpad shows "SWIPE CARD"
    When the customer inserts card "EMV AMMEX CREDIT"
    Then the pinpad shows "donate"
    When the customer presses "$0.50" on the pinpad
    Then the receipt matches its baseline
    And the transaction log matches its baseline
