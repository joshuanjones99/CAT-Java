package com.verifone.cat.rig;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

/**
 * Finds, reads and validates a rig file. Port of {@code petrotest/rig/loader.py}.
 *
 * <p>A rig is named ({@code lab}) or given as a path to a {@code .yaml} file. A name is looked
 * up in the rigs directory: {@code $PETROTEST_RIGS_DIR} when set, the variable the Python CAT
 * reads, so both CATs find the same files; otherwise the Python CAT checkout's {@code rigs/}.
 * This repository commits no rig file, so no rig's addresses are copied here.
 *
 * <p>Every failure is a {@link RigConfigException} (an {@link IllegalArgumentException}), and a
 * schema failure names the file and the field path, as {@code file: field.path: message}. A
 * malformed rig file must fail here, not later as a mysterious timeout.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port). Reads files only; nothing here opens a connection.
 */
public final class RigLoader {

    public static final String RIGS_DIR_ENV = "PETROTEST_RIGS_DIR";
    public static final Path DEFAULT_RIGS_DIR = Path.of("C:\\gitrepos\\CAT\\rigs");

    /** Quoted in messages so a person can tell what the file is supposed to be. */
    private static final String SCHEMA = "DESIGN.md §8";

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true)
            .registerModule(new SimpleModule("rig-scalars")
                    .addDeserializer(Duration.class, new DurationDeserializer())
                    .addDeserializer(String.class, new StrictStringDeserializer())
                    .addDeserializer(Boolean.class, new StrictBooleanDeserializer()));

    private static final Pattern MISSING_CREATOR = Pattern.compile("Missing required creator property '([^']+)'");

    private RigLoader() {}

    /**
     * The directory the rig files live in: {@code $PETROTEST_RIGS_DIR} when set and not blank, else
     * {@link #DEFAULT_RIGS_DIR}. Not checked for existence here; a missing rig says where it looked.
     */
    public static Path rigsDir(Map<String, String> env) {
        String fromEnv = env.get(RIGS_DIR_ENV);
        return fromEnv == null || fromEnv.isBlank() ? DEFAULT_RIGS_DIR : Path.of(fromEnv);
    }

    /** Resolves {@code nameOrPath} against {@code rigsDir} and reads it. */
    public static Rig load(String nameOrPath, Path rigsDir) {
        return loadResolved(nameOrPath, rigsDir).rig();
    }

    /**
     * As {@link #load}, but keeps the file it came from, which the WireMock mapping path needs.
     *
     * <p>A name must match the file stem: {@code --rig NAME} resolves by file name, so a file that
     * says {@code name: other} under {@code lab.yaml} is refused. A path is read as given, and its
     * name is not checked against its file name.
     */
    public static LoadedRig loadResolved(String nameOrPath, Path rigsDir) {
        if (nameOrPath == null || nameOrPath.isBlank()) {
            throw new RigConfigException("a rig name is required (DESIGN.md §5.5)");
        }
        String name = nameOrPath.strip();
        if (name.endsWith(".yaml") || name.endsWith(".yml")) {
            return loadFile(Path.of(name));
        }
        Path file = null;
        for (String suffix : new String[] {".yaml", ".yml"}) {
            Path candidate = rigsDir.resolve(name + suffix);
            if (Files.isRegularFile(candidate)) {
                file = candidate;
                break;
            }
        }
        if (file == null) {
            List<String> known = availableRigs(rigsDir);
            throw new RigConfigException("No rig file at " + rigsDir.resolve(name + ".yaml") + ". Name a rig in "
                    + rigsDir + " (set $" + RIGS_DIR_ENV + " to look elsewhere), or pass a path to a .yaml file."
                    + " Known rigs: " + (known.isEmpty() ? "(none)" : String.join(", ", known)));
        }
        LoadedRig loaded = loadFile(file);
        String stem = stripSuffix(file.getFileName().toString());
        if (!loaded.rig().name().equals(stem)) {
            throw new RigConfigException(file + ": name: is '" + loaded.rig().name() + "' but the file is '" + stem
                    + "'. They must match: --rig NAME resolves by file name (§5.5).");
        }
        return loaded;
    }

    /** The rig names available in {@code rigsDir}, sorted, for a message that lists them. */
    public static List<String> availableRigs(Path rigsDir) {
        if (!Files.isDirectory(rigsDir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(rigsDir)) {
            return files.filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString())
                    .filter(n -> n.endsWith(".yaml") || n.endsWith(".yml"))
                    .map(RigLoader::stripSuffix)
                    .sorted()
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    /** Reads and validates one rig file by path. */
    public static LoadedRig loadFile(Path file) {
        String text;
        try {
            text = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RigConfigException("cannot read rig file " + file + ": " + e.getMessage(), e);
        }

        JsonNode tree;
        try {
            tree = YAML.readTree(text);
        } catch (IOException e) {
            throw new RigConfigException(file + ": not valid YAML (" + SCHEMA + "): " + e.getMessage(), e);
        }
        if (tree == null || tree.isMissingNode() || tree.isNull()) {
            throw new RigConfigException(file + ": file is empty; expected the " + SCHEMA + " rig schema");
        }
        if (!tree.isObject()) {
            throw new RigConfigException(file + ": expected a YAML mapping of the " + SCHEMA
                    + " rig schema, got " + describeNode(tree));
        }

        try {
            return new LoadedRig(YAML.treeToValue(tree, Rig.class), file.toAbsolutePath());
        } catch (JsonProcessingException e) {
            throw new RigConfigException(describeFailure(file, e), e);
        }
    }

    // -- error text ---------------------------------------------------------------------------

    /**
     * {@code file: invalid rig configuration (DESIGN.md §8):} then one line per problem, naming the
     * field. Jackson stops at its first failure, so there is one line here where pydantic gives all.
     */
    private static String describeFailure(Path file, JsonProcessingException e) {
        String where = pathOf(e);
        String message;
        if (e instanceof UnrecognizedPropertyException u) {
            where = appendField(where, u.getPropertyName());
            message = "unknown field — check the spelling against " + SCHEMA
                    + " (unknown keys are rejected, never ignored)";
        } else if (e instanceof MismatchedInputException m && m.getOriginalMessage() != null
                && MISSING_CREATOR.matcher(m.getOriginalMessage()).find()) {
            Matcher named = MISSING_CREATOR.matcher(m.getOriginalMessage());
            named.find();
            where = appendField(where, named.group(1));
            message = "Field required";
        } else if (e instanceof InvalidFormatException f) {
            message = f.getOriginalMessage();
        } else if (e.getCause() instanceof IllegalArgumentException cause) {
            // A record's compact constructor said what is wrong, in the words the Python validators use.
            message = cause.getMessage();
        } else if (e instanceof JsonMappingException m && m.getOriginalMessage() != null) {
            message = m.getOriginalMessage();
        } else {
            message = e.getOriginalMessage();
        }
        return file + ": invalid rig configuration (" + SCHEMA + "):\n  - "
                + (where.isEmpty() ? "<root>" : where) + ": " + message;
    }

    /** The field path of a Jackson failure, as {@code registers.0.host}, the form pydantic prints. */
    private static String pathOf(JsonProcessingException e) {
        if (!(e instanceof JsonMappingException m)) {
            return "";
        }
        StringBuilder path = new StringBuilder();
        for (JsonMappingException.Reference ref : m.getPath()) {
            if (ref.getFieldName() != null) {
                appendSegment(path, ref.getFieldName());
            } else if (ref.getIndex() >= 0) {
                appendSegment(path, String.valueOf(ref.getIndex()));
            }
        }
        return path.toString();
    }

    private static String appendField(String path, String field) {
        StringBuilder sb = new StringBuilder(path);
        appendSegment(sb, field);
        return sb.toString();
    }

    private static void appendSegment(StringBuilder path, String segment) {
        if (path.length() > 0) {
            path.append('.');
        }
        path.append(segment);
    }

    private static String describeNode(JsonNode node) {
        if (node.isArray()) {
            return "a list";
        }
        if (node.isTextual()) {
            return "a string";
        }
        if (node.isNumber()) {
            return "a number";
        }
        return node.getNodeType().toString().toLowerCase(java.util.Locale.ROOT);
    }

    private static String stripSuffix(String fileName) {
        return fileName.endsWith(".yaml") ? fileName.substring(0, fileName.length() - 5)
                : fileName.substring(0, fileName.length() - 4);
    }

    // -- scalar deserializers -----------------------------------------------------------------

    /**
     * A rig duration: {@code 15s}, a bare number of seconds, or the grammar in {@link Durations}.
     * This is the only place a rig file's duration becomes a {@link Duration}.
     */
    static final class DurationDeserializer extends StdDeserializer<Duration> {

        private static final long serialVersionUID = 1L;

        DurationDeserializer() {
            super(Duration.class);
        }

        @Override
        public Duration deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
            Object raw = switch (p.currentToken()) {
                case VALUE_STRING -> p.getText();
                case VALUE_NUMBER_INT -> p.getNumberValue();
                case VALUE_NUMBER_FLOAT -> p.getDecimalValue();
                case VALUE_TRUE -> Boolean.TRUE;
                case VALUE_FALSE -> Boolean.FALSE;
                default -> p.getText();
            };
            try {
                return Durations.parse(raw);
            } catch (IllegalArgumentException e) {
                throw JsonMappingException.from(p, e.getMessage(), e);
            }
        }
    }

    /**
     * A string must be a YAML string. Unquoted, {@code 057.00} is a float and would arrive as
     * {@code 57.0}, which never matches the installed build; pydantic's strict model refuses it too.
     */
    static final class StrictStringDeserializer extends StdDeserializer<String> {

        private static final long serialVersionUID = 1L;

        StrictStringDeserializer() {
            super(String.class);
        }

        @Override
        public String deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
            if (p.currentToken() != JsonToken.VALUE_STRING) {
                throw JsonMappingException.from(p, "Input should be a valid string; quote the value if it is a "
                        + "number, e.g. \"057.00\"");
            }
            return p.getText();
        }
    }

    /** A boolean must be {@code true} or {@code false}, not the string {@code "true"}. */
    static final class StrictBooleanDeserializer extends StdDeserializer<Boolean> {

        private static final long serialVersionUID = 1L;

        StrictBooleanDeserializer() {
            super(Boolean.class);
        }

        @Override
        public Boolean deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
            return switch (p.currentToken()) {
                case VALUE_TRUE -> Boolean.TRUE;
                case VALUE_FALSE -> Boolean.FALSE;
                default -> throw JsonMappingException.from(p, "Input should be a valid boolean (true or false)");
            };
        }
    }
}
