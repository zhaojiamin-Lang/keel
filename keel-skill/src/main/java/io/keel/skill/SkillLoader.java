package io.keel.skill;

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

import io.keel.core.SkillDefinition;

public final class SkillLoader {

    public static final String DEFAULT_DIRECTORY = "skills";

    public List<SkillDefinition> load() {
        return load(DEFAULT_DIRECTORY);
    }

    public List<SkillDefinition> load(String classpathDirectory) {
        return doLoad(classpathDirectory, true);
    }

    /**
     * 宽松加载：classpath 目录不存在时返回空列表而非抛异常。
     * 用于可选的灰度 skill 目录（如 {@code skills-gray}）——业务没有灰度需求时零负担。
     */
    public List<SkillDefinition> loadOptional(String classpathDirectory) {
        return doLoad(classpathDirectory, false);
    }

    private List<SkillDefinition> doLoad(String classpathDirectory, boolean required) {
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
            if (required) {
                throw new IllegalArgumentException(
                        "No classpath directory found: " + directory);
            }
            // 可选目录：没有灰度 skill 是正常状态，不是错误
            return List.of();
        }

        List<SkillDefinition> skills = new ArrayList<>();
        for (URL root : roots) {
            collectSkills(root, skills);
        }

        rejectDuplicateNames(skills);

        if (skills.isEmpty()) {
            throw new IllegalArgumentException(
                    "No skill yaml files found in classpath directory: " + directory);
        }
        return List.copyOf(skills);
    }

    private void collectSkills(URL root, List<SkillDefinition> skills) {
        String protocol = root.getProtocol();
        if ("file".equals(protocol)) {
            collectFileSkills(root, skills);
        } else if ("jar".equals(protocol)) {
            collectJarSkills(root, skills);
        } else {
            throw new IllegalArgumentException(
                    "Unsupported classpath protocol: " + protocol);
        }
    }

    private void collectFileSkills(URL root, List<SkillDefinition> skills) {
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
                    skills.add(load(input));
                }
            }
        } catch (Exception exception) {
            throw new IllegalArgumentException(
                    "Unable to load skills from classpath directory: " + root,
                    exception);
        }
    }

    private void collectJarSkills(URL root, List<SkillDefinition> skills) {
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
                        skills.add(load(input));
                    }
                }
            }
        } catch (Exception exception) {
            throw new IllegalArgumentException(
                    "Unable to load skills from classpath jar: " + root,
                    exception);
        }
    }

    public SkillDefinition load(InputStream input) {
        Objects.requireNonNull(input, "input");

        Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
        Map<String, Object> document = asStringMap(yaml.load(input));
        String name = requiredString(document.get("name"), "name");

        return SkillDefinition.builder()
                .name(name)
                .description(stringValue(document.get("description")))
                .version(stringValue(document.get("version")))
                .tools(defaultList(stringList(document.get("tools")), List.of()))
                .requireCitation(booleanValue(document.get("requireCitation"), false))
                .writable(booleanValue(document.get("writable"), false))
                .build();
    }

    private void rejectDuplicateNames(List<SkillDefinition> skills) {
        Map<String, SkillDefinition> seen = new LinkedHashMap<>();
        for (SkillDefinition skill : skills) {
            if (skill == null || skill.getName() == null) {
                continue;
            }
            SkillDefinition existing = seen.putIfAbsent(skill.getName(), skill);
            if (existing != null) {
                throw new IllegalStateException("Duplicate skill name: " + skill.getName());
            }
        }
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
        return context == null ? SkillLoader.class.getClassLoader() : context;
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

    private static boolean booleanValue(Object value, boolean fallback) {
        if (value instanceof Boolean booleanValue) {
            return booleanValue;
        }
        return value == null ? fallback : Boolean.parseBoolean(String.valueOf(value));
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
