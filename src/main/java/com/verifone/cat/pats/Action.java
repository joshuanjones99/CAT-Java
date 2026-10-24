package com.verifone.cat.pats;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;

import com.verifone.cat.pos.LocatorTypes;

/**
 * One PATS action: its {@code name} and its {@code <field name= value=>} children, in document order. Port of
 * {@code petrotest.pats.case.Action}.
 *
 * <p>Field names are matched without regard to case, as PATS lower-cases them; {@link #get} and
 * {@link #raw} keep the Python's two readings of an empty field: a field present with {@code value=""} is
 * {@code ""} to {@link #raw}, and absent is {@link Optional#empty()}.
 *
 * @param name   the action's name, as written ({@code VALIDATION_BY_TEXT}, {@code SLEEP})
 * @param fields the fields in order; a field may repeat (PATS reads a map, so the first non-empty value wins)
 * @param index  the action's position in its case, from zero
 */
public record Action(String name, List<Field> fields, int index) {

    /** One {@code <field name= value=>}; the name is as written. */
    public record Field(String name, String value) {
    }

    public Action {
        fields = List.copyOf(fields);
    }

    /** The first non-empty value of field {@code name}, else {@code defaultValue}. */
    public String get(String name, String defaultValue) {
        String value = raw(name).orElse("");
        return value.isEmpty() ? defaultValue : value;
    }

    /** {@link #get(String, String)} with the empty string as the default. */
    public String get(String name) {
        return get(name, "");
    }

    /**
     * Field {@code name}'s first non-empty value; {@code ""} when the field is present but every value is empty;
     * empty when the field is not there at all. A handler that throws on a missing field must not throw on an
     * empty one.
     */
    public Optional<String> raw(String name) {
        String wanted = name.toUpperCase(Locale.ROOT);
        List<String> values = fields.stream()
                .filter(field -> field.name().toUpperCase(Locale.ROOT).equals(wanted))
                .map(Field::value)
                .toList();
        if (values.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(values.stream().filter(value -> !value.isEmpty()).findFirst().orElse(""));
    }

    /** The action as the Python prints it: {@code #3 SLEEP(SLEEP='5000')}, without empty fields or the device. */
    @Override
    public String toString() {
        List<String> shown = new ArrayList<>();
        for (Field field : fields) {
            if (!field.value().isEmpty() && !field.name().equals("DEVICEID") && !field.name().equals("CONTEXT")) {
                shown.add(field.name() + "=" + LocatorTypes.pyRepr(field.value()));
            }
        }
        return "#" + (index + 1) + " " + name + "(" + shown.stream().collect(Collectors.joining(", ")) + ")";
    }
}
