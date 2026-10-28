@PETROCORE-61338
Feature: Charity Donations

  # PETROCORE-61338, "Charity Donations - COUPON sale with Rest for charity" in PATS.
  # Rewritten 2026-10-07, step for step, from PETROCORE-61338-TestCase.py (hand-migrated from PATS);
  # its migration notes stay in that file's git history.
  Scenario: Coupon sale with rest for charity
    Given the register is idle
    When the cashier enters "32232211"
    And the cashier keys TOTAL
    And the cashier keys 10.00 on the OTHER MOP key
    And the cashier enters "11"
    Then the POS shows error "E1163": "NO CHANGE ALLOWED"
    When the cashier keys 09.50 on the OTHER MOP key
    And the cashier enters "11"
    Then the receipt matches its baseline
    And the transaction log matches its baseline
