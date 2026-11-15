package app.morphe.extension.shared;

import android.content.Context;
import android.util.JsonReader;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

@SuppressWarnings("ALL")
public class StringRef {
    /**
     * Simple class to assist with apps that currently cannot use resource patches.
     */
    public record StringKeyLookup(Map<String, String> stringMap) {
        public StringKeyLookup(Map<String, String> stringMap) {
            this.stringMap = Objects.requireNonNull(stringMap);
        }

        public String getString(String key, Object... args) {
            String str = stringMap.get(key);
            if (str == null) {
                Logger.printException(() -> "Unknown string key: " + key);
                return key;
            }

            return String.format(str, args);
        }
    }

    /**
     * Where the patches put the translated strings, a JSON object for each locale.
     * The strings of the default language are string resources.
     */
    private static final String TRANSLATIONS_DIR = "morphe/strings/";

    /**
     * String resource with the name of the translations file of the language.
     */
    private static final String TRANSLATIONS_FILE_STRING = "morphe_translations_file";

    @Nullable
    private static String translationsLanguageTag;
    private static Map<String, String> translations = Collections.emptyMap();

    /**
     * @return The string translated to the language of the context,
     *         or null if it has no translation and the string resource is to be used.
     */
    @Nullable
    static String getTranslation(Context context, String name) {
        return getTranslations(context).get(name);
    }

    /**
     * @return If the language of the context has translations, which string resources do not have.
     */
    static boolean hasTranslations(Context context) {
        return !getTranslations(context).isEmpty();
    }

    private static synchronized Map<String, String> getTranslations(Context context) {
        Locale locale = context.getResources().getConfiguration().locale;
        String languageTag = locale.toLanguageTag();
        if (!languageTag.equals(translationsLanguageTag)) {
            translationsLanguageTag = languageTag;
            translations = loadTranslations(context, locale);
        }
        return translations;
    }

    private static Map<String, String> loadTranslations(Context context, Locale locale) {
        // The patches name the translations file of each language in a string resource of its
        // values folder, so Android picks the file the way it picks the strings of the language.
        // It is empty in the default values, as the strings of the default language are resources.
        final int identifier = ResourceUtils.getIdentifier(context, ResourceType.STRING, TRANSLATIONS_FILE_STRING);
        String name = identifier == 0 ? "" : context.getResources().getString(identifier);
        if (name.isEmpty()) {
            Logger.printDebug(() -> "No translations for: " + locale);
            return Collections.emptyMap();
        }

        final long start = System.nanoTime();
        Map<String, String> strings = new HashMap<>();
        try (JsonReader reader = new JsonReader(new BufferedReader(new InputStreamReader(
                context.getAssets().open(TRANSLATIONS_DIR + name + ".json"), StandardCharsets.UTF_8), 32768))) {
            reader.beginObject();
            while (reader.hasNext()) {
                // A later string of the same name replaces the earlier one.
                strings.put(reader.nextName(), reader.nextString());
            }
            reader.endObject();
        } catch (IOException | RuntimeException ex) {
            Logger.printException(() -> "Could not read translations: " + name, ex);
            return Collections.emptyMap();
        }

        final long millis = (System.nanoTime() - start) / 1_000_000;
        Logger.printInfo(() -> "Loaded " + strings.size() + " translations of " + name + " in " + millis + "ms");
        return strings;
    }

    // must use a thread safe map, as this class is used both on and off the main thread
    private static final Map<String, StringRef> strings = Collections.synchronizedMap(new HashMap<>());

    /**
     * Returns a cached instance.
     * Should be used if the same String could be loaded more than once.
     *
     * @param id string resource name/id
     * @see #sf(String)
     */
    @NonNull
    public static StringRef sfc(@NonNull String id) {
        StringRef ref = strings.get(id);
        if (ref == null) {
            ref = new StringRef(id);
            strings.put(id, ref);
        }
        return ref;
    }

    /**
     * Creates a new instance, but does not cache the value.
     * Should be used for Strings that are loaded exactly once.
     *
     * @param id string resource name/id
     * @see #sfc(String)
     */
    @NonNull
    public static StringRef sf(@NonNull String id) {
        return new StringRef(id);
    }

    /**
     * Gets string value by string id, shorthand for <code>sfc(id).toString()</code>
     *
     * @param id string resource name/id
     * @return String value from string.xml
     */
    @NonNull
    public static String str(@NonNull String id) {
        return sfc(id).toString();
    }

    /**
     * Gets string value by string id, shorthand for <code>sfc(id).toString()</code> and formats the string
     * with given args.
     *
     * @param id   string resource name/id
     * @param args the args to format the string with
     * @return String value from string.xml formatted with given args
     */
    @NonNull
    public static String str(@NonNull String id, Object... args) {
        return String.format(str(id), args);
    }

    /**
     * Creates a StringRef object that'll not change its value
     *
     * @param value value which toString() method returns when invoked on returned object
     * @return Unique StringRef instance, its value will never change
     */
    @NonNull
    public static StringRef constant(@NonNull String value) {
        final StringRef ref = new StringRef(value);
        ref.resolved = true;
        return ref;
    }

    /**
     * Shorthand for <code>constant("")</code>
     * Its value always resolves to empty string
     */
    @NonNull
    public static final StringRef empty = constant("");

    private String value;
    private boolean resolved;

    public StringRef(@NonNull String resName) {
        this.value = resName;
    }

    @Override
    @NonNull
    public synchronized String toString() {
        if (!resolved) {
            String resolvedValue = ResourceUtils.getString(value);
            value = resolvedValue == null ? "Unknown string" : resolvedValue;
            resolved = true;
        }
        return value;
    }
}
