@PETROCORE-136480 @plan_end
Feature: Charity-Donations-Combined

  # PETROCORE-136480, 'Charity Donations (indoor and outdoor)- Department Report' in Xray (PETROCORE-135803).
  # Written by tools/gen_cadence_features.py from PATS's AutomationTests/TestCases/PETROCORE-136480/PETROCORE-136480-TestCase.xml
  # at petroapps 3f6848a8e7e, one step per PATS action; regenerate rather than edit.
  Scenario: Charity Donations (indoor and outdoor)- Department Report
    When the register prints flash report "4" from main menu "1", functions menu "20"
    Then the receipt matches "PETROCORE-136480-Receipt.txt"
