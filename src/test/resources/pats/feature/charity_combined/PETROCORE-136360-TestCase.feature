@PETROCORE-136360
Feature: Charity-Donations-Combined

  # PETROCORE-136360, 'PREPAY-QUANTITY-CREDIT' in Xray (PETROCORE-135803).
  # Written by tools/gen_cadence_features.py from PATS's AutomationTests/TestCases/PETROCORE-136360/PETROCORE-136360-TestCase.xml
  # at petroapps 3f6848a8e7e, one step per PATS action; regenerate rather than edit.
  Scenario: PREPAY-QUANTITY-CREDIT
    When the cashier touches ITEM_PANEL_N(145416)
    And the cashier touches VFI_KEYPAD_WIDGET_OVERLAY_NUMERIC_KEYPAD_KEY_N(4)
    And the cashier touches VFI_KEYPAD_WIDGET_OVERLAY_NUMERIC_KEYPAD_KEY_OK
    Then VFI_KEYPAD_WIDGET_OVERLAY_NUMERIC_KEYPAD_KEY_CANCEL shows "Gallon"
    And VFI_KEYPAD_WIDGET_OVERLAY_NUMERIC_KEYPAD_KEY_OK shows "$"
    And INPUT_OVERLAY_VIEW_LABEL1 shows "Enter Amount/Volume"
    When the cashier touches VFI_KEYPAD_WIDGET_OVERLAY_NUMERIC_KEYPAD_KEY_N(4)
    And the cashier touches VFI_KEYPAD_WIDGET_OVERLAY_NUMERIC_KEYPAD_KEY_N(00)
    And the cashier touches VFI_KEYPAD_WIDGET_OVERLAY_NUMERIC_KEYPAD_KEY_CANCEL
    And the cashier touches REVIEW_OVERLAY_VIEW_ITEM_N(13631490)
    And the cashier touches ITEM_PANEL_N(145397)
    And the cashier touches ITEM_PANEL_N(145395)
    Then the pinpad prompts "SWIPE CARD"
    When the card "EMV VISA CREDIT" is presented at "VIPERPAY"
    Then the receipt matches "PETROCORE-136360-Receipt.txt"
    And the transaction log matches "PETROCORE-136360-Tlog.xml" by "TLOG-RULE.txt"
    When pump "4" fuels "12.48"
    And pump "4" hangs up
    And PATS pauses 10000 ms
    And the cashier touches FUEL_PANEL_0_FUEL_BUTTON_N_DATA(4)
    And the cashier touches FUEL_POINT_DETAIL_OVERLAY_VIEW_MAIN_PANEL_FUNCTION_BUTTON_LABEL_N(4)
    Then XP_BUTTON_REVIEW_OVERLAY_VIEW_ITEM_SELECTED shows "12.48"
    When the cashier touches EXPRESS_PANEL_VIEW_BUTTON_ID_N(3)
    Then the receipt matches "PETROCORE-136360-1-Receipt.txt"
    When PATS pauses 2000 ms
    Then the transaction log of "DCR4" matches "PETROCORE-136360-1-Tlog.xml" by "TLOG-RULE.txt"
    When PATS pauses 5000 ms
