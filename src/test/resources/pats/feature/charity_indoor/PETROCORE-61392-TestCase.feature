@PETROCORE-61392
Feature: Charity Donations

  # PETROCORE-61392, "Charity Donations - Mobile Fuel Preset Postpay with Amount" in PATS.
  # Rewritten 2026-10-07, step for step, from PETROCORE-61392-TestCase.py (hand-migrated from PATS);
  # its migration notes stay in that file's git history.
  Scenario: Mobile fuel preset postpay with amount
    Given the register is idle
    When the cashier presets 14.50 on pump 5
    And pump 5 dispenses 14.50
    And the customer hangs up pump 5
    And the cashier claims the fuel sale on pump 5
    And the cashier keys TOTAL
    And the cashier keys the OTHER MOP key and picks entry 24
    Then the pinpad shows "donate"
    When the customer presses "$0.50" on the pinpad
    Then the pinpad shows "CHOOSE A PROGRAM"
    When the customer presses "MOBILE TEST" on the pinpad
    And the cashier presses ENTER
    Then the receipt matches its baseline
    And the transaction log matches its baseline
