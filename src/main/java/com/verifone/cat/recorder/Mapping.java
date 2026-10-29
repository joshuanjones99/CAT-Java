package com.verifone.cat.recorder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.verifone.cat.validate.PyRepr;

/**
 * What one observation resolved to: {@code mapped}, {@code unmapped} or {@code ambiguous}. Port of
 * {@code petrotest.recorder.catalogue.Mapping}; {@link #asDict} is the file form.
 *
 * @param status     {@code mapped}, {@code unmapped} or {@code ambiguous}
 * @param locator    the catalogue name, when mapped (a redacted tap keeps it)
 * @param group      the catalogue group
 * @param args       the {@code {n}} slot values in order: digits, kept as text so {@code 00} survives
 * @param kwargs     named slots, e.g. {@code label=Total} for a text family
 * @param via        {@code id} or {@code text}, when mapped
 * @param candidates the names an ambiguous observation could be
 * @param reason     why it is not mapped, or that it was redacted
 */
public record Mapping(String status, String locator, String group, List<String> args, Map<String, String> kwargs,
        String via, List<String> candidates, String reason) {

    public Mapping {
        args = List.copyOf(args == null ? List.of() : args);
        kwargs = kwargs == null ? Map.of() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(kwargs));
        candidates = List.copyOf(candidates == null ? List.of() : candidates);
        reason = reason == null ? "" : reason;
    }

    /** A {@code status} with no locator: {@code unmapped} or {@code ambiguous}. */
    static Mapping of(String status, List<String> candidates, String reason) {
        return new Mapping(status, null, null, List.of(), Map.of(), null, candidates, reason);
    }

    /** A mapped observation. */
    static Mapping mapped(String locator, String group, List<String> args, Map<String, String> kwargs, String via) {
        return new Mapping("mapped", locator, group, args, kwargs, via, List.of(), "");
    }

    public boolean isMapped() {
        return "mapped".equals(status);
    }

    /** The file form: {@code status}, then the locator's fields when there is one, candidates and reason when set. */
    public Map<String, Object> asDict() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", status);
        if (locator != null && !locator.isEmpty()) {
            out.put("locator", locator);
            out.put("group", group);
            out.put("via", via);
            out.put("args", new ArrayList<>(args));
            if (!kwargs.isEmpty()) {
                out.put("kwargs", new LinkedHashMap<>(kwargs));
            }
        }
        if (!candidates.isEmpty()) {
            out.put("candidates", new ArrayList<>(candidates));
        }
        if (!reason.isEmpty()) {
            out.put("reason", reason);
        }
        return out;
    }

    /** The inverse of {@link #asDict}. */
    public static Mapping fromDict(Map<String, Object> data) {
        List<String> args = new ArrayList<>();
        if (data.get("args") instanceof List<?> list) {
            list.forEach(item -> args.add(PyRepr.str(item)));
        }
        Map<String, String> kwargs = new LinkedHashMap<>();
        if (data.get("kwargs") instanceof Map<?, ?> map) {
            map.forEach((k, v) -> kwargs.put(PyRepr.str(k), PyRepr.str(v)));
        }
        List<String> candidates = new ArrayList<>();
        if (data.get("candidates") instanceof List<?> list) {
            list.forEach(item -> candidates.add(PyRepr.str(item)));
        }
        return new Mapping(PyRepr.str(data.get("status")), text(data.get("locator")), text(data.get("group")), args,
                kwargs, text(data.get("via")), candidates, data.get("reason") == null ? "" : PyRepr.str(data.get("reason")));
    }

    private static String text(Object value) {
        return value == null ? null : PyRepr.str(value);
    }
}
