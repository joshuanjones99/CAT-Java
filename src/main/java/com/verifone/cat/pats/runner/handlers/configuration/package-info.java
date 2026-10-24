/**
 * The configuration action family: IMPORT_CONFIGURATION, UPDATE_CONFIGURATION, HTTP_SIM_EVENT, the three report
 * actions, REPORT_PRINT and CLOUD_HTTP_REQUEST. Registered by
 * {@link com.verifone.cat.pats.runner.handlers.configuration.ConfigurationFamily#register}.
 *
 * <p>It reaches the register, the cloud, the close recovery and FEPSIM v2 through seams ({@code RegisterPos},
 * {@code CloudSender}, {@code StuckCloseRecovery}, {@code BuypassV2}), whose production bindings belong to other
 * families. UNVERIFIED AGAINST HARDWARE: fake-backed tests only.
 */
package com.verifone.cat.pats.runner.handlers.configuration;
