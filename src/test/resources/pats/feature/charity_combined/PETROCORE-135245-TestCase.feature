@PETROCORE-135245
Feature: Charity-Donations-Combined

  # PETROCORE-135245, 'CharityConfig_cash-false_cardIndoor-true_cardOutdoor-false - Import Configurations' in Xray (PETROCORE-135803).
  # Written by tools/gen_cadence_features.py from PATS's AutomationTests/TestCases/PETROCORE-135245/PETROCORE-135245-TestCase.xml
  # at petroapps 3f6848a8e7e, one step per PATS action; regenerate rather than edit.
  Scenario: CharityConfig_cash-false_cardIndoor-true_cardOutdoor-false - Import Configurations
    When the Commander imports configuration "charitycfg_cash-false_cardIndoor-true_cardOutdoor-false"
