@PETROCORE-37042
Feature: Charity-Donations-Outdoor

  # PETROCORE-37042, 'Refresh-Config' in Xray (PETROCORE-70979).
  # Written by tools/gen_cadence_features.py from PATS's AutomationTests/testcases/PETROCORE-37042/PETROCORE-37042-TestCase.xml
  # at petroapps 3f6848a8e7e, one step per PATS action; regenerate rather than edit.
  Scenario: Refresh-Config
    When PATS pauses 60000 ms
    And the Commander runs "crefreshcfg"
    And PATS pauses 60000 ms
