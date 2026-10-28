@PETROCORE-61357
Feature: Charity Donations

  # PETROCORE-61357, "Charity Donations- Cash sale - PLU Promo" in PATS.
  # Rewritten 2026-10-07, step for step, from PETROCORE-61357-TestCase.py (hand-migrated from PATS);
  # its migration notes stay in that file's git history.
  Scenario: Cash sale with plu promo
    Given the register is idle
    When the cashier rings up PLU 0000000009123
    And the cashier totals the sale
    And the customer pays 27.00 in cash
    Then the pinpad shows "donate"
    When the customer presses "$0.63" on the pinpad
    Then the receipt matches its baseline
    And the transaction log matches its baseline
