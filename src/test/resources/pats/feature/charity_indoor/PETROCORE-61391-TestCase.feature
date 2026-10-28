@PETROCORE-61391
Feature: Charity Donations

  # PETROCORE-61391, "Charity Donations - Mobile Merchandise Scan Token" in PATS.
  # Rewritten 2026-10-07, step for step, from PETROCORE-61391-TestCase.py (hand-migrated from PATS);
  # its migration notes stay in that file's git history.
  Scenario: Mobile merchandise scan token
    Given the register is idle
    When the cashier keys PLU 9998
    And the cashier keys TOTAL
    And the cashier keys the OTHER MOP key and picks entry 24
    Then the pinpad shows "donate"
    When the customer presses "$0.50" on the pinpad
    Then the pinpad shows "CHOOSE A PROGRAM"
    When the customer presses "MPPA2" on the pinpad
    Then the POS asks "PLEASE SCAN QR CODE"
    When the cashier enters "123456"
    Then the receipt matches its baseline
    And the transaction log matches its baseline
