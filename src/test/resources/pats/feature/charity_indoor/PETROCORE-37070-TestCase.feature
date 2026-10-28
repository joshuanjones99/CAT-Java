@PETROCORE-37070
Feature: Charity Donations

  # PETROCORE-37070, "PETROCORE-37070-FUEL-DCR-INIT" in PATS.
  # Rewritten 2026-10-07, step for step, from PETROCORE-37070-TestCase.py (hand-migrated from PATS);
  # its migration notes stay in that file's git history.
  Scenario: Fuel dcr control function init
    When the Commander initialises fuel
    And the Commander initialises the DCRs
