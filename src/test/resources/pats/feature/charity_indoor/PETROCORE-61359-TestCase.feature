@PETROCORE-61359
Feature: Charity Donations

  # PETROCORE-61359, "Charity Donations- Credit sale - PLU Promo" in PATS.
  # Rewritten 2026-10-07, step for step, from PETROCORE-61359-TestCase.py (hand-migrated from PATS);
  # its migration notes stay in that file's git history.
  Scenario: Credit sale promotional sku with donation
    Given the register is idle
    When the cashier rings up PLU 0000000009123
    And the cashier totals the sale
    And the customer pays by credit
    Then the pinpad shows "SWIPE CARD"
    When the customer inserts card "EMV AMMEX CREDIT"
    Then the pinpad shows "donate"
    When the customer presses "$10.00" on the pinpad
    Then the receipt matches its baseline
    And the transaction log matches its baseline
