@PETROCORE-61390
Feature: Charity Donations

  # PETROCORE-61390, "Charity Donations- Card Naxml Mix And Match" in PATS.
  # Rewritten 2026-10-07, step for step, from PETROCORE-61390-TestCase.py (hand-migrated from PATS);
  # its migration notes stay in that file's git history.
  Scenario: Credit combo sale with donation and cashback declined
    Given the register is idle
    When the cashier keys 2 of PLU 8001
    And the cashier keys TOTAL
    And the cashier keys the CREDIT key
    Then the pinpad shows "SWIPE CARD"
    When the customer inserts card "DISCOVER"
    Then the pinpad shows "donate"
    When the customer presses "$10.00" on the pinpad
    Then the pinpad shows "CASHBACK"
    When the customer presses "NO" on the pinpad
    Then the receipt matches its baseline
    And the transaction log matches its baseline
