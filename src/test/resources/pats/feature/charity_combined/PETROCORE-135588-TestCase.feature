@PETROCORE-135588
Feature: Charity-Donations-Combined

  # PETROCORE-135588, 'Charity Donations- Credit sale - PLU Promo- Charity disbale for cash and oudoor' in Xray (PETROCORE-135803).
  # Written by tools/gen_cadence_features.py from PATS's AutomationTests/TestCases/PETROCORE-135588/PETROCORE-135588-TestCase.xml
  # at petroapps 3f6848a8e7e, one step per PATS action; regenerate rather than edit.
  Scenario: Charity Donations- Credit sale - PLU Promo- Charity disbale for cash and oudoor
    When PATS runs ADD_ITEM with "PLU=0000000009123; QUANTITY=1; MODIFIER=0; IDC=N; PRICEOVER=N; PLUNOTFUND PRICE=N; DISCOUNT=0; DISCOUNT KEY=SOFT KEY; SELECT SOFTKEY=N; USING MENU=N; CHOICE=N; TAX EXC=N; SELECT_FILE=NO FILE"
    And the register keys TOTAL
    And the register keys MOP "0x7d" with "0000"
    Then the pinpad prompts "SWIPE CARD"
    When the card "EMV AMMEX CREDIT" is presented at "VIPERPAY"
    Then the pinpad prompts "donate"
    When the pinpad is answered "$10.00"
    Then the receipt matches "PETROCORE-135588-Receipt.txt"
    And the transaction log matches "PETROCORE-135588-Tlog.xml" by "TLOG-RULE.txt"
