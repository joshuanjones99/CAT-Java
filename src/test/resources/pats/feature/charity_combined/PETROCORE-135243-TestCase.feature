@PETROCORE-135243
Feature: Charity-Donations-Combined

  # PETROCORE-135243, 'CharityConfig_cash-true_cardIndoor-false_cardOutdoor-false - Import Configurations' in Xray (PETROCORE-135803).
  # Written by tools/gen_cadence_features.py from PATS's AutomationTests/TestCases/PETROCORE-135243/PETROCORE-135243-TestCase.xml
  # at petroapps 3f6848a8e7e, one step per PATS action; regenerate rather than edit.
  Scenario: CharityConfig_cash-true_cardIndoor-false_cardOutdoor-false - Import Configurations
    When C-Site is sent "PUT" "pcms/v1/config/{service_id}/charities" with payload "CharityConfig_cash-true_cardIndoor-false_cardOutdoor-false.json"
