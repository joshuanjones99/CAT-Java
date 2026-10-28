@PETROCORE-61348
Feature: Charity Donations

  # PETROCORE-61348, "Charity Donataions - Mobile sale with Preset Amounts" in PATS.
  # Rewritten 2026-10-07, step for step, from PETROCORE-61348-TestCase.py (hand-migrated from PATS);
  # its migration notes stay in that file's git history.
  Scenario: Mobile sale with preset amounts
    Given the register is idle
    When the cashier rings up PLU 9998
    And the cashier totals the sale
    And the customer pays by "MOBILE"
    Then the pinpad shows "donate"
    When the customer presses "$0.50" on the pinpad
    Then the pinpad shows "CHOOSE A PROGRAM"
    When the customer presses "MOBILE TEST" on the pinpad
    Then the receipt matches its baseline
    And the transaction log matches its baseline
