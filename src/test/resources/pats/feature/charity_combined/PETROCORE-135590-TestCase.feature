@PETROCORE-135590
Feature: Charity-Donations-Combined

  # PETROCORE-135590, 'Charity Donations - Cash sale with Rest for charity(charity indoor card disabled)' in Xray (PETROCORE-135803).
  # Written by tools/gen_cadence_features.py from PATS's AutomationTests/TestCases/PETROCORE-135590/PETROCORE-135590-TestCase.xml
  # at petroapps 3f6848a8e7e, one step per PATS action; regenerate rather than edit.
  Scenario: Charity Donations - Cash sale with Rest for charity(charity indoor card disabled)
    When PATS runs ADD_ITEM with "PLU=9999; QUANTITY=1; MODIFIER=0; IDC=N; PRICEOVER=N; PLUNOTFUND PRICE=N; DISCOUNT=0; DISCOUNT KEY=SOFT KEY; SELECT SOFTKEY=N; USING MENU=N; CHOICE=N; TAX EXC=N; SELECT_FILE=NO FILE"
    And the register keys TOTAL
    And the register keys MOP "CASH" with "1100"
    Then the pinpad prompts "donate"
    When the pinpad is answered "$0.51"
    Then the receipt matches "PETROCORE-135590-Receipt.txt"
    And the transaction log matches "PETROCORE-135590-Tlog.xml" by "TLOG-RULE.txt"
