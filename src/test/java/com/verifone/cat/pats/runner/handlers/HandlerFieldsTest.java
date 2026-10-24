package com.verifone.cat.pats.runner.handlers;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.verifone.cat.pats.runner.Fields;
import com.verifone.cat.pats.runner.handlers.devices.DevicesFamily;
import com.verifone.cat.pats.runner.handlers.sale.SaleFamily;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * The Python's {@code test_pats_feature} check, for the sale and devices families: every field a handler reads is
 * listed in {@link Fields#FIELDS_READ}, and every action a family registers has a field list. The sources are read
 * from the tree, so a handler that starts reading a new field fails here until the table lists it.
 */
class HandlerFieldsTest {

    /** A field read in a handler: {@code action.get("X"}, {@code action.raw("X"}, or {@code require(action, "X"}. */
    private static final Pattern READ = Pattern.compile(
            "(?:get|raw|require)\\(\\s*(?:action\\s*,\\s*)?\"([^\"]+)\"");

    /** The fields every action reads, which the table does not list per action. */
    private static final Set<String> EVERY_ACTION = Set.of("DEVICE", "DEVICEID");

    private static final Path SOURCE_ROOT = Path.of("src", "main", "java", "com", "verifone", "cat", "pats",
            "runner", "handlers");

    @Test
    void everyFieldASaleOrDevicesHandlerReadsIsListedInFieldsRead() throws IOException {
        Set<String> listed = Fields.FIELDS_READ.values().stream()
                .flatMap(List::stream)
                .collect(Collectors.toSet());
        Set<String> read = new TreeSet<>();
        for (Path source : sources()) {
            Matcher match = READ.matcher(Files.readString(source, StandardCharsets.UTF_8));
            while (match.find()) {
                read.add(match.group(1));
            }
        }
        read.removeAll(EVERY_ACTION);

        Set<String> unlisted = new TreeSet<>(read);
        unlisted.removeAll(listed);
        assertTrue(unlisted.isEmpty(), "the handlers read " + unlisted
                + ", which Fields.FIELDS_READ does not list: add each to the actions whose handler reads it");
    }

    @Test
    void everyActionTheSaleAndDevicesFamiliesRegisterHasAFieldList() {
        Set<String> registered = new TreeSet<>();
        registered.addAll(SaleFamily.ACTIONS);
        registered.addAll(DevicesFamily.ACTIONS);

        assertTrue(Fields.FIELDS_READ.keySet().containsAll(registered),
                "no field list for " + registered.stream()
                        .filter(name -> !Fields.FIELDS_READ.containsKey(name))
                        .collect(Collectors.toList()));
    }

    private static List<Path> sources() throws IOException {
        List<Path> files = new ArrayList<>();
        for (String family : List.of("sale", "devices")) {
            try (Stream<Path> listed = Files.list(SOURCE_ROOT.resolve(family))) {
                listed.filter(path -> path.toString().endsWith(".java")).forEach(files::add);
            }
        }
        assertTrue(!files.isEmpty(), "no handler sources found under " + SOURCE_ROOT.toAbsolutePath());
        return files;
    }
}
