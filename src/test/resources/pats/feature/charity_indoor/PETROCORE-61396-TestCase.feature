@PETROCORE-61396
Feature: Charity Donations

  # PETROCORE-61396, "CHARITY-IMPORT_CONFIGURATION" in PATS.
  # Rewritten 2026-10-07, step for step, from PETROCORE-61396-TestCase.py (hand-migrated from PATS);
  # its migration notes stay in that file's git history.
  Scenario: Import charity config
    Given the store imports the "charity" configuration
