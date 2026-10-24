@PETROCORE-135241
Feature: Charity-Donations-Combined

  # PETROCORE-135241, 'CharityConfig_cash-true_cardIndoor-true_cardOutdoor-false - Import Configurations' in Xray (PETROCORE-135803).
  # Written by tools/gen_cadence_features.py from PATS's AutomationTests/TestCases/PETROCORE-135241/PETROCORE-135241-TestCase.xml
  # at petroapps 3f6848a8e7e, one step per PATS action; regenerate rather than edit.
  Scenario: CharityConfig_cash-true_cardIndoor-true_cardOutdoor-false - Import Configurations
    When the Commander imports configuration "charitycfg_cash-true_cardIndoor-true_cardOutdoor-false"
