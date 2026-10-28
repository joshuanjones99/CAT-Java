@PETROCORE-61388
Feature: Charity Donations

  # PETROCORE-61388, "Charity Donations- Card Naxml Combo" in PATS.
  # Rewritten 2026-10-07, step for step, from PETROCORE-61388-TestCase.py (hand-migrated from PATS);
  # its migration notes stay in that file's git history.
  Scenario: Credit combo sale with preset donation
    Given the register is idle
    When the cashier rings up PLU 7001
    And the cashier rings up PLU 7002
    And the cashier totals the sale
    And the customer pays by credit
    Then the pinpad shows "SWIPE CARD"
    When the customer inserts card "EMV AMMEX CREDIT"
    Then the pinpad shows "donate"
    When the customer presses "$10.00" on the pinpad
    Then the receipt matches its baseline
    And the transaction log matches its baseline
