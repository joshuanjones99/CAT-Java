@PETROCORE-61393
Feature: Charity Donations

  # PETROCORE-61393, "Charity Donations - Mobile Fuel Prepay Quantity" in PATS.
  # Rewritten 2026-10-07, step for step, from PETROCORE-61393-TestCase.py (hand-migrated from PATS);
  # its migration notes stay in that file's git history.
  Scenario: Mobile fuel prepay quantity
    Given the register is idle
    When the cashier keys a prepay quantity of 5.00 on pump 4
    And the cashier enters "4"
    And the cashier keys TOTAL
    And the cashier keys the OTHER MOP key and picks entry 24
    Then the pinpad shows "donate"
    When the customer presses "$10.00" on the pinpad
    Then the pinpad shows "CHOOSE A PROGRAM"
    When the customer presses "MOBILE TEST" on the pinpad
    Then the receipt matches its baseline
    And the transaction log matches its baseline
    When pump 4 dispenses 5.61 of its prepay
    And the customer hangs up prepaid pump 4
