@PETROCORE-61432
Feature: Charity-Donations-Outdoor

  # PETROCORE-61432, 'Charity Outdoor POS-Login' in Xray (PETROCORE-70979).
  # Written by tools/gen_cadence_features.py from PATS's AutomationTests/testcases/PETROCORE-61432/PETROCORE-61432-TestCase.xml
  # at petroapps 3f6848a8e7e, one step per PATS action; regenerate rather than edit.
  Scenario: Charity Outdoor POS-Login
    Then IDLE_SCREEN_VIEW_PRODUCT_NAME is on screen
    When the cashier touches IDLE_SCREEN_VIEW_PRODUCT_NAME
    Then USER_ID_PASSWORD_SCREEN_VIEW_PROMPT_TEXT shows the text of "ENTER_ID"
    When the cashier touches VFI_KEYPAD_WIDGET_OVERLAY_NUMERIC_KEYPAD_KEY_N(1)
    And the cashier touches VFI_KEYPAD_WIDGET_OVERLAY_NUMERIC_KEYPAD_KEY_OK
    And PATS pauses 1000 ms
    Then USER_ID_PASSWORD_SCREEN_VIEW_PROMPT_TEXT shows the text of "ENTER_PASSWORD"
    When the cashier touches VFI_KEYPAD_WIDGET_OVERLAY_NUMERIC_KEYPAD_KEY_N(1)
    And the cashier touches VFI_KEYPAD_WIDGET_OVERLAY_NUMERIC_KEYPAD_KEY_N(2)
    And the cashier touches VFI_KEYPAD_WIDGET_OVERLAY_NUMERIC_KEYPAD_KEY_N(3)
    And the cashier touches VFI_KEYPAD_WIDGET_OVERLAY_NUMERIC_KEYPAD_KEY_OK
    And PATS pauses 2000 ms
    Then REVIEW_OVERLAY_VIEW_ITEM_N(13631488) shows "1. OPEN TILL for Drawer 1"
    When the cashier touches REVIEW_OVERLAY_VIEW_ITEM_N(13631488)
    And PATS pauses 2000 ms
    Then CALCULATOR_VIEW_TITLE_TEXT shows the text of "ENTER_OPEN_DRAWER_AMOUNT"
    When the cashier touches CALCULATOR_VIEW_OVERLAY_NUMERIC_KEYPAD_KEY_N(1)
    And the cashier touches CALCULATOR_VIEW_OVERLAY_NUMERIC_KEYPAD_KEY_N(00)
    And the cashier touches CALCULATOR_VIEW_OVERLAY_NUMERIC_KEYPAD_KEY_N(00)
    And the cashier touches CALCULATOR_VIEW_OVERLAY_NUMERIC_KEYPAD_KEY_OK
    And PATS pauses 25000 ms
