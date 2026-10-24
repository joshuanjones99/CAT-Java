@PETROCORE-135589
Feature: Charity-Donations-Combined

  # PETROCORE-135589, 'Charity Donations - Cash Prepay Merchandise- Charity disable for indoor and outdoor card' in Xray (PETROCORE-135803).
  # Written by tools/gen_cadence_features.py from PATS's AutomationTests/TestCases/PETROCORE-135589/PETROCORE-135589-TestCase.xml
  # at petroapps 3f6848a8e7e, one step per PATS action; regenerate rather than edit.
  Scenario: Charity Donations - Cash Prepay Merchandise- Charity disable for indoor and outdoor card
    When PATS runs ADD_ITEM with "PLU=32232211; QUANTITY=1; MODIFIER=0; IDC=N; PRICEOVER=N; PLUNOTFUND PRICE=N; DISCOUNT=0; DISCOUNT KEY=SOFT KEY; SELECT SOFTKEY=N; USING MENU=N; CHOICE=N; TAX EXC=N; SELECT_FILE=NO FILE"
    And the register prepays "500" on pump "1" by MOP "0x7d", product "1", grade "N"
    And the register keys TOTAL
    And the register keys MOP "CASH" with "1450"
    Then the receipt matches "PETROCORE-135589-Receipt.txt"
    And the transaction log matches "PETROCORE-135589-Tlog.xml" by "TLOG-RULE.txt"
    When pump "1" fuels "5.00"
    And pump "1" hangs up
    And PATS pauses 5000 ms
