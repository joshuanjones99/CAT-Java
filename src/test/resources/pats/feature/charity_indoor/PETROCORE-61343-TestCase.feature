@PETROCORE-61343
Feature: Charity Donations

  # PETROCORE-61343, "Charity Donations - Credit sale with Preset Amounts" in PATS.
  # Rewritten 2026-10-07, step for step, from PETROCORE-61343-TestCase.py (hand-migrated from PATS);
  # its migration notes stay in that file's git history.
  Scenario: Credit sale with preset donation
    Given the register is idle
    When the cashier keys PLU 9112 with modifier 1
    And the cashier keys TOTAL
    And the cashier keys the CREDIT key
    Then the pinpad shows "SWIPE CARD"
    When the customer inserts card "EMV AMMEX CREDIT"
    Then the pinpad shows "donate"
    When the customer presses "$0.50" on the pinpad
    Then the receipt matches its baseline
    And the transaction log matches its baseline
