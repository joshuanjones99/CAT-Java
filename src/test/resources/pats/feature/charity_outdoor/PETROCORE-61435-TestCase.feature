@PETROCORE-61435
Feature: Charity-Donations-Outdoor

  # PETROCORE-61435, 'DCR $1.00 No Carwash' in Xray (PETROCORE-70979).
  # Written by tools/gen_cadence_features.py from PATS's AutomationTests/testcases/PETROCORE-61435/PETROCORE-61435-TestCase.xml
  # at petroapps 3f6848a8e7e, one step per PATS action; regenerate rather than edit.
  Scenario: DCR $1.00 No Carwash
    Then the dispenser "DCR1" prompts "WELCOME"
    When the customer presses soft key L2 at the dispenser "DCR1"
    Then the dispenser "DCR1" prompts "Insert Credit Card"
    When the card "Master Card2" is presented at "DCR1"
    Then the dispenser "DCR1" prompts "ENTER ZIP CODE"
    When the customer keys "33759" at the dispenser "DCR1"
    Then the dispenser "DCR1" prompts "Car Wash Today?"
    When the customer presses soft key L4 at the dispenser "DCR1"
    Then the dispenser "DCR1" prompts "Donate to"
    When the customer presses soft key L3 at the dispenser "DCR1"
    And pump "1" fuels "4.23" on hose "1"
    And PATS pauses 2000 ms
    And pump "1" hangs up
    And PATS pauses 2000 ms
    Then the receipt matches "PETROCORE-61435-Receipt.txt"
    And the transaction log matches "PETROCORE-61435-Tlog.xml" by "TLOG-RULE.txt"
