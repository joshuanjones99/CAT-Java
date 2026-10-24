@PETROCORE-37041
Feature: Charity-Donations-Outdoor

  # PETROCORE-37041, 'DCR-Driver-INIT' in Xray (PETROCORE-70979).
  # Written by tools/gen_cadence_features.py from PATS's AutomationTests/testcases/PETROCORE-37041/PETROCORE-37041-TestCase.xml
  # at petroapps 3f6848a8e7e, one step per PATS action; regenerate rather than edit.
  Scenario: DCR-Driver-INIT
    When PATS pauses 60000 ms
    And the Commander runs "cdcrdriverinit"
