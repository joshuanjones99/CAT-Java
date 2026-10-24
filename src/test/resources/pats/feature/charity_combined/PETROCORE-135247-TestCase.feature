@PETROCORE-135247
Feature: Charity-Donations-Combined

  # PETROCORE-135247, 'CharityConfig_cash-false_cardIndoor-false_cardOutdoor-false - Import Configurations' in Xray (PETROCORE-135803).
  # Written by tools/gen_cadence_features.py from PATS's AutomationTests/TestCases/PETROCORE-135247/PETROCORE-135247-TestCase.xml
  # at petroapps 3f6848a8e7e, one step per PATS action; regenerate rather than edit.
  # CAT edit (2026-10-20, rig owner's yes): PATS's CONFIGURATION_URL names {SERVICEID};
  # CloudActionHandler replaces only the literal {service_id}, so this config went out to
  # a service called "{SERVICEID}" (in PATS too) and the plan never switched charity off.
  # Written {service_id}, as 135242 and 135243 write it.
  Scenario: CharityConfig_cash-false_cardIndoor-false_cardOutdoor-false - Import Configurations
    When PATS runs CLOUD_HTTP_REQUEST with "DEVICE=cloud_config; HTTP_METHOD=PUT; CONFIGURATION_URL=pcms/v1/config/{service_id}/charities; SELECT_PAYLOAD_FILE=CharityConfig_cash-false_cardIndoor-false_cardOutdoor-false.json"
