@PETROCORE-61382
Feature: Charity Donations

  # PETROCORE-61382, "Charity Donations - Cash Underrun Completion" in PATS.
  # Rewritten 2026-10-07, step for step, from PETROCORE-61382-TestCase.py (hand-migrated from PATS);
  # its migration notes stay in that file's git history.
  Scenario: Fuel cash underrun completion
    Given the register is idle
    When the cashier claims the fuel sale on pump 1
    And the cashier keys TOTAL
    And the cashier keys the CASH key
    Then the receipt matches its baseline
    And the transaction log matches its baseline
