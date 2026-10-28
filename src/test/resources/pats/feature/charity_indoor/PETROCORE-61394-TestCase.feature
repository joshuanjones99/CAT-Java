@PETROCORE-61394 @plan_end
Feature: Charity Donations

  # PETROCORE-61394, "Charity Donations - Department Report" in PATS.
  # Rewritten 2026-10-07, step for step, from PETROCORE-61394-TestCase.py (hand-migrated from PATS);
  # its migration notes stay in that file's git history.
  Scenario: Department report
    When the cashier prints flash report 4 from functions menu 20 of main menu 1
    Then the receipt matches its baseline
