@PETROCORE-61430
Feature: Charity-Donations-Outdoor

  # PETROCORE-61430, 'Charity Outdoor Import Configuration' in Xray (PETROCORE-70979).
  # Written by tools/gen_cadence_features.py from PATS's AutomationTests/testcases/PETROCORE-61430/PETROCORE-61430-TestCase.xml
  # at petroapps 3f6848a8e7e, one step per PATS action; regenerate rather than edit.
  Scenario: Charity Outdoor Import Configuration
    When the Commander imports configuration "charityoutdoor"
