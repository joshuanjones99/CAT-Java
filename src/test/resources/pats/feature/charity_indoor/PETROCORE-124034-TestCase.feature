@PETROCORE-124034
Feature: Charity Donations

  # PETROCORE-124034, "Import-buypass-host-simulator-config" in PATS.
  # Rewritten 2026-10-07, step for step, from PETROCORE-124034-TestCase.py (hand-migrated from PATS);
  # its migration notes stay in that file's git history.
  Scenario: Import buypass host simulator config
    Given the store imports the "buypass-host-simulator-config" configuration
