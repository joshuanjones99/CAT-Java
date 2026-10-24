package com.verifone.cat.collect;

import java.util.List;

/**
 * One per-case copy block of {@code CICDScript.bat} (lines 474-690).
 *
 * <p>{@code order} is where PATS looks: {@code "dist"} is {@code <id>/<dist>/}, {@code "case"} is {@code <id>/}, first
 * hit wins. {@code missing} is the echo when nothing matches, or {@code null} where PATS is silent ({@code {id}} is
 * the case key). {@code recursive} is {@code xcopy /s}. {@code wholeDir} is {@code if exist <dir>\}: any file below
 * {@code subdir} counts. {@code check} is a folder to test instead of {@code subdir}, or {@code null}.
 */
public record Rule(String name, String pattern, String dest, List<String> order, String missing, String subdir,
        String check, boolean recursive, boolean wholeDir) {}
