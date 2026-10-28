@PETROCORE-61342
Feature: Charity Donations

  # PETROCORE-61342, "Charity Donations - Credit/ Debit/ Mobile sale with Preset Amounts + timeout" in PATS.
  # Rewritten 2026-10-07, step for step, from PETROCORE-61342-TestCase.py (hand-migrated from PATS);
  # its migration notes stay in that file's git history.
  Scenario: Credit sale donation prompt times out
    Given the register is idle
    When the cashier rings up PLU 32232211
    And the cashier totals the sale
    And the customer pays by credit
    Then the pinpad shows "SWIPE CARD"
    When the customer inserts card "MASTERCARD_1"
    And the customer presses "CREDIT" on the pinpad
    Then the pinpad shows "donate"
    And the pinpad's "donate" prompt clears by itself within 90s
    And the receipt matches its baseline
    And the transaction log matches its baseline
