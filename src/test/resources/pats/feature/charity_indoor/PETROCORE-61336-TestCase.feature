@PETROCORE-61336
Feature: Charity Donations

  # PETROCORE-61336, "Charity Donations - Cash sale - No Thanks" in PATS.
  # Rewritten 2026-10-07, step for step, from PETROCORE-61336-TestCase.py (hand-migrated from PATS);
  # its migration notes stay in that file's git history.
  Scenario: Cash sale donation declined
    Given the register is idle
    When the cashier rings up PLU 9999
    And the cashier totals the sale
    And the customer pays 11.00 in cash
    Then the pinpad shows "donate"
    When the customer presses "No Thanks" on the pinpad
    Then the receipt matches its baseline
    And the transaction log matches its baseline
