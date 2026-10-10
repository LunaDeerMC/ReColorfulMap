package cn.lunadeer.reColorfulMap.utils.configuration;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Strict, staged configuration loading. Reading an existing file never rewrites it. */
public final class ConfigurationManager {
    private ConfigurationManager() { }

    public static YamlConfiguration readYaml(File file) throws IOException, InvalidConfigurationException {
        return readYaml(Files.readString(file.toPath(), StandardCharsets.UTF_8));
    }

    public static YamlConfiguration readYaml(String contents) throws InvalidConfigurationException {
        try {
            LoaderOptions options = new LoaderOptions();
            options.setAllowDuplicateKeys(false);
            Object document = new Yaml(new SafeConstructor(options)).load(contents);
            if (document != null && !(document instanceof Map<?, ?>)) {
                throw new InvalidConfigurationException("The YAML document must be a mapping");
            }
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.loadFromString(contents);
            return yaml;
        } catch (InvalidConfigurationException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new InvalidConfigurationException(e);
        }
    }

    /** Values are fully converted into new objects before any public field is modified. */
    public static Prepared prepare(Class<? extends ConfigurationFile> clazz, YamlConfiguration yaml) throws Exception {
        Map<Field, Object> values = new LinkedHashMap<>();
        for (Field field : clazz.getFields()) {
            if (!Modifier.isStatic(field.getModifiers()) || Modifier.isFinal(field.getModifiers())) continue;
            String key = camelToKebab(field.getName());
            values.put(field, readValue(yaml, key, field, field.get(null)));
        }
        return new Prepared(values);
    }

    public static Prepared prepare(Class<? extends ConfigurationFile> clazz, File file) throws Exception {
        return prepare(clazz, file.exists() ? readYaml(file) : new YamlConfiguration());
    }

    public static void load(Class<? extends ConfigurationFile> clazz, File file) throws Exception {
        Prepared prepared = prepare(clazz, file);
        if (!file.exists()) savePrepared(prepared, file);
        prepared.publish();
    }

    /** Version changes no longer move or rewrite administrators' files while reading them. */
    public static void load(Class<? extends ConfigurationFile> clazz, File file, String versionFieldName) throws Exception {
        load(clazz, file);
    }

    public static void save(Class<? extends ConfigurationFile> clazz, File file) throws Exception {
        Map<Field, Object> values = new LinkedHashMap<>();
        for (Field field : clazz.getFields()) {
            if (Modifier.isStatic(field.getModifiers()) && !Modifier.isFinal(field.getModifiers())) {
                values.put(field, field.get(null));
            }
        }
        savePrepared(new Prepared(values), file);
    }

    private static Object readValue(YamlConfiguration yaml, String key, Field field, Object fallback) throws Exception {
        if (ConfigurationPart.class.isAssignableFrom(field.getType())) {
            if (yaml.contains(key) && !yaml.isConfigurationSection(key)) throw typeError(key, "mapping");
            Object part = field.getType().getDeclaredConstructor().newInstance();
            for (Field child : field.getType().getFields()) {
                if (Modifier.isStatic(child.getModifiers()) || Modifier.isFinal(child.getModifiers())) continue;
                child.set(part, readValue(yaml, key + "." + camelToKebab(child.getName()), child, child.get(part)));
            }
            return part;
        }
        if (!yaml.contains(key)) return fallback instanceof List<?> list ? List.copyOf(list) : fallback;
        Object value = yaml.get(key);
        Class<?> type = field.getType();
        if (type == String.class && value instanceof String) return value;
        if (type == boolean.class && value instanceof Boolean) return value;
        if (type == int.class && value instanceof Integer) return value;
        if (type == long.class && (value instanceof Integer || value instanceof Long)) return ((Number) value).longValue();
        if ((type == double.class || type == float.class) && value instanceof Number number && Double.isFinite(number.doubleValue())) {
            if (type == float.class) return number.floatValue();
            return number.doubleValue();
        }
        if (List.class.isAssignableFrom(type) && value instanceof List<?> list && list.stream().allMatch(String.class::isInstance)) return List.copyOf(list);
        throw typeError(key, type.getSimpleName());
    }

    private static InvalidConfigurationException typeError(String key, String expected) {
        return new InvalidConfigurationException(key + " must be a " + expected);
    }

    public static void saveYaml(YamlConfiguration yaml, File file) throws IOException {
        Path target = file.toPath().toAbsolutePath();
        Files.createDirectories(target.getParent());
        Path temporary = Files.createTempFile(target.getParent(), ".configuration-", ".tmp");
        try {
            Files.writeString(temporary, yaml.saveToString(), StandardCharsets.UTF_8);
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void savePrepared(Prepared prepared, File file) throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.options().width(250);
        for (Map.Entry<Field, Object> value : prepared.values.entrySet()) {
            writeValue(yaml, camelToKebab(value.getKey().getName()), value.getKey(), value.getValue());
        }
        saveYaml(yaml, file);
    }

    private static void writeValue(YamlConfiguration yaml, String key, Field field, Object value) throws Exception {
        if (value instanceof ConfigurationPart) {
            for (Field child : value.getClass().getFields()) {
                if (!Modifier.isStatic(child.getModifiers())) writeValue(yaml, key + "." + camelToKebab(child.getName()), child, child.get(value));
            }
        } else {
            yaml.set(key, value);
        }
        if (field.isAnnotationPresent(Comments.class)) yaml.setComments(key, List.of(field.getAnnotation(Comments.class).value()));
    }

    private static String camelToKebab(String camel) {
        return camel.replaceAll("([a-z])([A-Z]+)", "$1-$2").toLowerCase(java.util.Locale.ROOT);
    }

    public static final class Prepared {
        private final Map<Field, Object> values;
        private Prepared(Map<Field, Object> values) { this.values = Map.copyOf(values); }
        public void publish() throws IllegalAccessException {
            // Every field and value has been validated during prepare; no user code runs here.
            for (Map.Entry<Field, Object> value : values.entrySet()) value.getKey().set(null, value.getValue());
        }
    }
}
