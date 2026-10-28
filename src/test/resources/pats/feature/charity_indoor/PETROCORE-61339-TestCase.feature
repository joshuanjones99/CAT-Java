@PETROCORE-61339
Feature: Charity Donations

  # PETROCORE-61339, "Charity Donations - Cash sale with Round to next dollar" in PATS.
  # Rewritten 2026-10-07, step for step, from PETROCORE-61339-TestCase.py (hand-migrated from PATS);
  # its migration notes stay in that file's git history.
  Scenario: Cash sale with round to next dollar
    Given the register is idle
    When the cashier rings up PLU 9999
    And the cashier totals the sale
    And the customer pays 11.00 in cash
    Then the pinpad shows "donate"
    When the customer presses "$0.51" on the pinpad
    Then the receipt matches its baseline
    And the transaction log matches its baseline
