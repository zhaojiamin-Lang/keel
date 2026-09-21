package io.keel.harness;

import java.io.IOException;
import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Loads {@link EvalCase} instances from YAML files in a classpath directory.
 */
public final class EvalCaseLoader {

    public List<EvalCase> load(String classpathDirectory) {
        String directory = normalizeDirectory(classpathDirectory);
        ClassLoader classLoader = getClassLoader();

        List<URL> roots;
        try {
            Enumeration<URL> resources = classLoader.getResources(directory);
            roots = new ArrayList<>();
            while (resources.hasMoreElements()) {
                roots.add(resources.nextElement());
            }
        } catch (IOException exception) {
            throw new IllegalArgumentException(
                    "Unable to list classpath directory: " + directory,
                    exception);
        }

        if (roots.isEmpty()) {
            throw new IllegalArgumentException(
                    "No classpath directory found: " + directory);
        }

        List<EvalCase> cases = new ArrayList<>();
        for (URL root : roots) {
            collectCases(root, cases);
        }

        if (cases.isEmpty()) {
            throw new IllegalArgumentException(
                    "No YAML eval cases found in classpath directory: " + directory);
        }
        return List.copyOf(cases);
    }

    private void collectCases(URL root, List<EvalCase> cases) {
        String protocol = root.getProtocol();
        if ("file".equals(protocol)) {
            collectFileCases(root, cases);
        } else if ("jar".equals(protocol)) {
            collectJarCases(root, cases);
        } else {
            throw new IllegalArgumentException(
                    "Unsupported classpath protocol: " + protocol);
        }
    }

    private void collectFileCases(URL root, List<EvalCase> cases) {
        try {
            Path directory = Paths.get(root.toURI());
            List<Path> files;
            try (Stream<Path> paths = Files.walk(directory, 1)) {
                files = paths
                        .filter(Files::isRegularFile)
                        .filter(this::isYamlFile)
                        .sorted(Comparator.comparing(Path::getFileName))
                        .toList();
            }

            for (Path file : files) {
                try (InputStream input = Files.newInputStream(file)) {
                    cases.add(load(input));
                }
            }
        } catch (Exception exception) {
            throw new IllegalArgumentException(
                    "Unable to load eval cases from classpath directory: " + root,
                    exception);
        }
    }

    private void collectJarCases(URL root, List<EvalCase> cases) {
        try {
            JarURLConnection connection = (JarURLConnection) root.openConnection();
            String entryName = connection.getEntryName();
            String prefix = entryName == null || entryName.isBlank()
                    ? ""
                    : (entryName.endsWith("/") ? entryName : entryName + "/");

            try (JarFile jarFile = connection.getJarFile()) {
                List<JarEntry> entries = new ArrayList<>();
                Enumeration<JarEntry> allEntries = jarFile.entries();
                while (allEntries.hasMoreElements()) {
                    JarEntry entry = allEntries.nextElement();
                    if (!entry.isDirectory()
                            && entry.getName().startsWith(prefix)
                            && entry.getName().indexOf('/', prefix.length()) < 0
                            && isYamlFileName(entry.getName())) {
                        entries.add(entry);
                    }
                }
                entries.sort(Comparator.comparing(JarEntry::getName));

                for (JarEntry entry : entries) {
                    try (InputStream input = jarFile.getInputStream(entry)) {
                        cases.add(load(input));
                    }
                }
            }
        } catch (Exception exception) {
            throw new IllegalArgumentException(
                    "Unable to load eval cases from classpath jar: " + root,
                    exception);
        }
    }

    public EvalCase load(InputStream input) {
        Objects.requireNonNull(input, "input");

        Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
        Map<String, Object> document = asStringMap(yaml.load(input));
        String id = requiredString(document.get("id"), "id");

        EvalCase.Builder builder = EvalCase.builder()
                .id(id)
                .skill(stringValue(document.get("skill")))
                .requireCitation(booleanValue(document.get("requireCitation")))
                .allowedIssueIds(stringList(document.get("allowedIssueIds")))
                .forbiddenSubstrings(
                        defaultList(stringList(document.get("forbiddenSubstrings")), List.of()))
                .expectWrite(booleanValue(document.get("expectWrite")));

        if (document.get("request") != null) {
            Map<String, Object> request = asStringMap(document.get("request"));
            builder.tenantId(stringValue(request.get("tenantId")))
                    .subjectId(stringValue(request.get("subjectId")))
                    .resourceIds(defaultList(stringList(request.get("resourceIds")), List.of()))
                    .input(stringValue(request.get("input")))
                    .idempotencyKey(stringValue(request.get("idempotencyKey")));
        }

        return builder.build();
    }

    private boolean isYamlFile(Path path) {
        return isYamlFileName(path.getFileName().toString());
    }

    private boolean isYamlFileName(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".yaml") || lower.endsWith(".yml");
    }

    private static String normalizeDirectory(String value) {
        Objects.requireNonNull(value, "classpathDirectory");
        String directory = value.trim();
        while (directory.startsWith("/")) {
            directory = directory.substring(1);
        }
        while (directory.endsWith("/")) {
            directory = directory.substring(0, directory.length() - 1);
        }
        if (directory.isBlank()) {
            throw new IllegalArgumentException("classpathDirectory must not be blank");
        }
        return directory;
    }

    private static ClassLoader getClassLoader() {
        ClassLoader context = Thread.currentThread().getContextClassLoader();
        return context == null ? EvalCaseLoader.class.getClassLoader() : context;
    }

    private static Map<String, Object> asStringMap(Object value) {
        if (!(value instanceof Map<?, ?> rawMap)) {
            throw new IllegalArgumentException("Expected a YAML mapping");
        }

        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
            result.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return result;
    }

    private static String requiredString(Object value, String field) {
        String result = stringValue(value);
        if (result == null || result.isBlank()) {
            throw new IllegalArgumentException("Missing non-blank field: " + field);
        }
        return result;
    }

    private static String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static boolean booleanValue(Object value) {
        if (value instanceof Boolean booleanValue) {
            return booleanValue;
        }
        return value != null && Boolean.parseBoolean(String.valueOf(value));
    }

    private static List<String> stringList(Object value) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof List<?> rawList)) {
            throw new IllegalArgumentException("Expected a YAML list");
        }

        List<String> result = new ArrayList<>();
        for (Object item : rawList) {
            result.add(String.valueOf(item));
        }
        return result;
    }

    private static List<String> defaultList(List<String> value, List<String> fallback) {
        return value == null ? fallback : value;
    }
}
