@PETROCORE-135585
Feature: Charity-Donations-Combined

  # PETROCORE-135585, 'Charity Donations - Credit sale when cash is disabled' in Xray (PETROCORE-135803).
  # Written by tools/gen_cadence_features.py from PATS's AutomationTests/TestCases/PETROCORE-135585/PETROCORE-135585-TestCase.xml
  # at petroapps 3f6848a8e7e, one step per PATS action; regenerate rather than edit.
  Scenario: Charity Donations - Credit sale when cash is disabled
    When PATS runs ADD_ITEM with "PLU=9112; QUANTITY=1; MODIFIER=1; IDC=N; PRICEOVER=N; PLUNOTFUND PRICE=N; DISCOUNT=0; DISCOUNT KEY=SOFT KEY; SELECT SOFTKEY=N; USING MENU=N; CHOICE=N; TAX EXC=N; SELECT_FILE=NO FILE"
    And the register keys TOTAL
    And the register keys MOP "0x7d" with "00000"
    Then the pinpad prompts "SWIPE CARD"
    When the card "EMV AMMEX CREDIT" is presented at "VIPERPAY"
    Then the pinpad prompts "donate"
    When the pinpad is answered "$0.50"
    Then the receipt matches "PETROCORE-135585-Receipt.txt"
    And the transaction log matches "PETROCORE-135585-Tlog.xml" by "TLOG-RULE.txt"
