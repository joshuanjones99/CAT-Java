@PETROCORE-61389
Feature: Charity Donations

  # PETROCORE-61389, "Charity Donations- Cash Naxml Mix And Match" in PATS.
  # Rewritten 2026-10-07, step for step, from PETROCORE-61389-TestCase.py (hand-migrated from PATS);
  # its migration notes stay in that file's git history.
  Scenario: Cash mix and match sale with rest for charity
    Given the register is idle
    When the cashier rings up PLU 8001
    And the cashier rings up PLU 8002
    And the cashier totals the sale
    And the customer pays 21.00 in cash
    Then the pinpad shows "donate"
    When the customer presses "$0.07" on the pinpad
    Then the receipt matches its baseline
    And the transaction log matches its baseline
