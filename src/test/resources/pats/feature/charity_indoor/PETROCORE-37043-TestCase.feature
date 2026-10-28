@PETROCORE-37043
Feature: Charity Donations

  # PETROCORE-37043, "PETROCORE-37043-POS-LOGIN" in PATS.
  # Rewritten 2026-10-07, step for step, from PETROCORE-37043-TestCase.py (hand-migrated from PATS);
  # its migration notes stay in that file's git history.
  Scenario: Cashier sign on and open till
    When the cashier signs on and opens the till with a 100.00 float
