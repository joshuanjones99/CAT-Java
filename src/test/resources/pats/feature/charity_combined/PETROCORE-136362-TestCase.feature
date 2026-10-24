@PETROCORE-136362
Feature: Charity-Donations-Combined

  # PETROCORE-136362, 'Prepay-OutDoor-2-PLU-CarWash-Credit' in Xray (PETROCORE-135803).
  # Written by tools/gen_cadence_features.py from PATS's AutomationTests/TestCases/PETROCORE-136362/PETROCORE-136362-TestCase.xml
  # at petroapps 3f6848a8e7e, one step per PATS action; regenerate rather than edit.
  Scenario: Prepay-OutDoor-2-PLU-CarWash-Credit
    Then the dispenser "DCR1" prompts "WELCOME"
    When the customer presses soft key L2 at the dispenser "DCR1"
    Then the dispenser "DCR1" prompts "Insert Credit Card"
    When the card "EMV VISA CREDIT" is presented at "DCR1"
    Then the dispenser "DCR1" prompts "ENTER ZIP CODE"
    When the customer keys "12345" at the dispenser "DCR1"
    Then the dispenser "DCR1" prompts "CAR WASH"
    When the customer presses soft key L3 at the dispenser "DCR1"
    Then the dispenser "DCR1" prompts "CAR-WASH-PLU-1"
    When the customer presses soft key L3 at the dispenser "DCR1"
    Then the dispenser "DCR1" prompts "REMOVE NOZZLE"
    When pump "1" fuels "12.73"
    And pump "1" hangs up
    Then the receipt matches "PETROCORE-136362-Receipt.txt"
    And the transaction log matches "PETROCORE-136362-Tlog.xml" by "TLOG-RULE.txt"
