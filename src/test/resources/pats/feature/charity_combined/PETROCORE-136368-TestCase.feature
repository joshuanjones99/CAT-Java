@PETROCORE-136368
Feature: Charity-Donations-Combined

  # PETROCORE-136368, 'PREPAY-OUTDOOR-DEBIT' in Xray (PETROCORE-135803).
  # Written by tools/gen_cadence_features.py from PATS's AutomationTests/TestCases/PETROCORE-136368/PETROCORE-136368-TestCase.xml
  # at petroapps 3f6848a8e7e, one step per PATS action; regenerate rather than edit.
  Scenario: PREPAY-OUTDOOR-DEBIT
    Then the dispenser "DCR1" prompts "WELCOME"
    When the customer presses soft key L1 at the dispenser "DCR1"
    Then the dispenser "DCR1" prompts "Insert debit Card"
    When the card "MSR DEBIT" is presented at "DCR1"
    Then the dispenser "DCR1" prompts "ENTER PIN"
    When the customer keys "1234" at the dispenser "DCR1"
    Then the dispenser "DCR1" prompts "CAR WASH"
    When the customer presses soft key L4 at the dispenser "DCR1"
    Then the dispenser "DCR1" prompts "REMOVE NOZZLE"
    When pump "1" fuels "20.00" on hose "4"
    And pump "1" hangs up
    Then the receipt matches "PETROCORE-136368-Receipt.txt"
    And the transaction log matches "PETROCORE-136368-Tlog.xml" by "TLOG-RULE.txt"
