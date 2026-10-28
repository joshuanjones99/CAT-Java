@PETROCORE-61395 @plan_end
Feature: Charity Donations

  # PETROCORE-61395, "Charity Donations - Summary Report" in PATS.
  # Rewritten 2026-10-07, step for step, from PETROCORE-61395-TestCase.py (hand-migrated from PATS);
  # its migration notes stay in that file's git history.
  Scenario: Summary report
    When the cashier prints flash report 1 from functions menu 20 of main menu 1
    Then the receipt matches its baseline
