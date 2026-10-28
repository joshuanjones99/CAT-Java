@PETROCORE-61386
Feature: Charity Donations

  # PETROCORE-61386, "Charity Donations- Card Fuel Pay Inside Merchandise" in PATS.
  # Rewritten 2026-10-07, step for step, from PETROCORE-61386-TestCase.py (hand-migrated from PATS);
  # its migration notes stay in that file's git history.
  Scenario: Dcr card fuel with pay inside merchandise
    Given the register is idle
    Then the DCR shows "WELCOME"
    When the customer presses L3 on the DCR
    Then the DCR shows "REMOVE NOZZLE"
    When pump 1 dispenses 21.23
    And the customer hangs up pump 1
    And the cashier claims the fuel sale on pump 1
    And the cashier keys PLU 9111
    And the cashier keys TOTAL
    And the cashier keys the CREDIT key
    Then the pinpad shows "SWIPE CARD"
    When the customer inserts card "AMEXCARD"
    Then the pinpad shows "donate"
    When the customer presses "$10.00" on the pinpad
    Then the receipt matches its baseline
    And the transaction log matches its baseline
