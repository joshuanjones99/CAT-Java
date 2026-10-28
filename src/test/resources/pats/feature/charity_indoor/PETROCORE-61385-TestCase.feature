@PETROCORE-61385
Feature: Charity Donations

  # PETROCORE-61385, "Charity Donations - Cash Fuel Preset Postpay" in PATS.
  # Rewritten 2026-10-07, step for step, from PETROCORE-61385-TestCase.py (hand-migrated from PATS);
  # its migration notes stay in that file's git history.
  Scenario: Fuel preset cash postpay with donation
    Given the register is idle
    When the cashier presets 14.50 on pump 5
    And pump 5 dispenses 14.50
    And the customer hangs up pump 5
    And the cashier claims the fuel sale on pump 5
    And the cashier keys TOTAL
    And the cashier keys 15.00 on the CASH key
    Then the pinpad shows "donate"
    When the customer presses "$0.96" on the pinpad
    Then the receipt matches its baseline
    And the transaction log matches its baseline
