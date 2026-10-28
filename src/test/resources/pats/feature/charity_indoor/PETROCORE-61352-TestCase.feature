@PETROCORE-61352
Feature: Charity Donations

  # PETROCORE-61352, "Charity Donations- No Charity" in PATS.
  # Rewritten 2026-10-07, step for step, from PETROCORE-61352-TestCase.py (hand-migrated from PATS);
  # its migration notes stay in that file's git history.
  Scenario: Cash sale no charity
    Given the register is idle
    When the cashier rings up PLU 9998
    And the cashier totals the sale
    And the customer pays 10.48 in cash
    Then the receipt matches its baseline
    And the transaction log matches its baseline
