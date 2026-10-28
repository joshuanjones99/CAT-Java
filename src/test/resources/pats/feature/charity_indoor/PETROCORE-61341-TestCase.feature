@PETROCORE-61341
Feature: Charity Donations

  # PETROCORE-61341, "Charity Donations - Cash sale with Rest for charity + timeout" in PATS.
  # Rewritten 2026-10-07, step for step, from PETROCORE-61341-TestCase.py (hand-migrated from PATS);
  # its migration notes stay in that file's git history.
  Scenario: Cash sale donation prompt times out
    Given the register is idle
    When the cashier rings up PLU 9998
    And the cashier totals the sale
    And the customer pays 11.00 in cash
    Then the pinpad shows "donate"
    And the pinpad's "donate" prompt clears by itself within 90s
    And the receipt matches its baseline
    And the transaction log matches its baseline
