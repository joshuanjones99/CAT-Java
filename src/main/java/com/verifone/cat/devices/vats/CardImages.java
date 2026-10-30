package com.verifone.cat.devices.vats;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Card image files by name, across several folders, indexed once. A port of the Python CAT's
 * {@code petrotest.devices.vats.cards.CardImages}.
 *
 * <p>Every one of the 88 PATS XML cases that sets {@code CARD_SIMULATION_IMAGE_FILE} sets it to {@code ""} (checked
 * 2026-10-06), so PATS loads nothing. A rig's {@code vats.card_images} maps a label to the image to use when a case
 * names none. Images are found by file name, case-insensitively, the first root to have it winning; the order is
 * {@link VatsSession#imageRoots}: petroapps' {@code Collis_Images}, every installed BTT newest first, then the staged
 * connector's own. Nothing is copied.
 *
 * <p>Deviation: within one root, a name found in two sub-folders resolves to the first in sorted path order (the
 * Python's {@code rglob} order is the file system's).
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
public final class CardImages {

    private final List<Path> roots;
    private Map<String, Path> index;

    public CardImages(List<Path> roots) {
        List<Path> kept = new ArrayList<>();
        for (Path root : roots) {
            if (root != null && Files.isDirectory(root)) {
                kept.add(root);
            }
        }
        this.roots = List.copyOf(kept);
    }

    /** The folders that exist, in search order. */
    public List<Path> roots() {
        return roots;
    }

    private Map<String, Path> build() {
        Map<String, Path> built = new HashMap<>();
        for (Path root : roots) {
            try (Stream<Path> walk = Files.walk(root)) {
                List<Path> files = walk.filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".xml"))
                        .sorted()
                        .collect(Collectors.toList());
                for (Path file : files) {
                    built.putIfAbsent(Cards.key(file.getFileName().toString()), file);
                }
            } catch (IOException e) {
                throw new UncheckedIOException("indexing card images under " + root, e);
            }
        }
        return built;
    }

    /** The image named {@code name} (an absolute path that exists, else a file name), or null. */
    public synchronized Path resolve(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String fileName = name;
        try {
            Path candidate = Path.of(name);
            if (candidate.isAbsolute() && Files.isRegularFile(candidate)) {
                return candidate;
            }
            Path last = candidate.getFileName();
            fileName = last == null ? name : last.toString();
        } catch (InvalidPathException e) {
            // not a path: look it up as a name
        }
        if (index == null) {
            index = build();
        }
        return index.get(Cards.key(fileName));
    }

    /** The folders searched, for a message; "no image folders found" when none exists. */
    public String describe() {
        if (roots.isEmpty()) {
            return "no image folders found";
        }
        return roots.stream().map(Path::toString).collect(Collectors.joining("; "));
    }
}
