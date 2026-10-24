/**
 * PATS legacy test cases, parsed: the {@code -TestCase.xml} and {@code .scenario} files in the petroapps
 * checkout, read action by action as PATS runs them.
 *
 * <p>A port of the parsing side of {@code petrotest.pats}: {@link com.verifone.cat.pats.Action} and
 * {@link com.verifone.cat.pats.CaseParser} ({@code case.py}'s {@code parse_actions}),
 * {@link com.verifone.cat.pats.ScenarioParser} ({@code scenario.py}: PATS's BDD steps read into the XML
 * case's action field names), and {@link com.verifone.cat.pats.Elements} ({@code elements.py}: a PATS
 * {@code WEB ELEMENT} literal looked up in the generated {@code pos} catalogue, never guessed).
 *
 * <p>Not here yet: the git-backed case store ({@code CaseStore}), the runner, the feature reader. Every
 * element ID a case names is resolved through {@link com.verifone.cat.pos.Locators}; a case file never
 * supplies an ID as a Java literal.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port): the parse is checked against the Python's behaviour on its
 * own fixtures, not against a register.
 */
package com.verifone.cat.pats;
