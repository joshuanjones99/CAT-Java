@PETROCORE-61337
Feature: Charity Donations

  # PETROCORE-61337, "Charity Donations - CHECK sale with Rest for charity" in PATS.
  # Rewritten 2026-10-07, step for step, from PETROCORE-61337-TestCase.py (hand-migrated from PATS);
  # its migration notes stay in that file's git history.
  Scenario: Check sale with rest for charity
    Given the register is idle
    When the cashier rings up PLU 32232211
    And the cashier totals the sale
    And the customer pays 10.00 by "CHECK"
    Then the POS shows error "E1163": "NO CHANGE ALLOWED"
    And the error message closes
    When the customer pays 09.50 by "CHECK"
    Then the receipt matches its baseline
    And the transaction log matches its baseline
