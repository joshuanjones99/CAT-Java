@PETROCORE-135962
Feature: Charity-Donations-Combined

  # PETROCORE-135962, 'LOGIN' in Xray (PETROCORE-135803).
  # Written by tools/gen_cadence_features.py from PATS's AutomationTests/TestCases/PETROCORE-135962/PETROCORE-135962-TestCase.xml
  # at petroapps 3f6848a8e7e, one step per PATS action; regenerate rather than edit.
  Scenario: LOGIN
    When the register logs in user "1" with password "123"
