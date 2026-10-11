import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import org.openstreetmap.josm.data.Preferences;
import org.openstreetmap.josm.data.validation.tests.MapCSSTagChecker;
import org.openstreetmap.josm.gui.mappaint.mapcss.MapCSSStyleSource;
import org.openstreetmap.josm.gui.tagging.presets.TaggingPresetReader;
import org.openstreetmap.josm.plugins.PluginInformation;
import org.openstreetmap.josm.plugins.PluginListParser;
import org.openstreetmap.josm.spi.preferences.Config;
import org.openstreetmap.josm.spi.preferences.IBaseDirectories;
import org.openstreetmap.josm.spi.preferences.IUrls;

public final class JosmResourceValidator {
    private JosmResourceValidator() {
    }

    public static void main(String[] args) {
        if (args.length != 2) {
            System.err.println("Usage: JosmResourceValidator.java <preset|style|validator|plugin-list> <file>");
            System.exit(2);
        }

        Path file = Path.of(args[1]).toAbsolutePath().normalize();
        try {
            validate(args[0], file);
            System.out.println("JOSM validation passed: " + file);
            System.exit(0);
        } catch (Exception e) {
            System.err.println("JOSM validation failed for " + file + ": " + e.getMessage());
            System.exit(1);
        }
    }

    private static void validate(String kind, Path file) throws Exception {
        if (!Files.isRegularFile(file)) {
            throw new IOException("resource file does not exist");
        }

        Path settings = Files.createTempDirectory("saarelai-josm-validation-");
        try {
            configureJosm(settings);
            switch (kind) {
                case "preset" -> validatePreset(file);
                case "style" -> validateStyle(file);
                case "validator" -> validateValidator(file);
                case "plugin-list" -> validatePluginList(file);
                default -> throw new IllegalArgumentException("unknown resource kind: " + kind);
            }
        } finally {
            try (var paths = Files.walk(settings)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException e) {
                        throw new RuntimeException("could not clean JOSM validation settings", e);
                    }
                });
            }
        }
    }

    private static void configureJosm(Path settings) throws IOException {
        Path preferences = Files.createDirectories(settings.resolve("prefs"));
        Path data = Files.createDirectories(settings.resolve("data"));
        Path cache = Files.createDirectories(settings.resolve("cache"));

        Config.setBaseDirectoriesProvider(new IBaseDirectories() {
            @Override
            public java.io.File getPreferencesDirectory(boolean create) {
                return preferences.toFile();
            }

            @Override
            public java.io.File getUserDataDirectory(boolean create) {
                return data.toFile();
            }

            @Override
            public java.io.File getCacheDirectory(boolean create) {
                return cache.toFile();
            }
        });
        Config.setUrlsProvider(new IUrls() {
            @Override
            public String getOSMWebsiteDependingOnSelectedApi() {
                return "https://www.openstreetmap.org";
            }

            @Override
            public String getBaseBrowseUrl() {
                return "https://www.openstreetmap.org";
            }

            @Override
            public String getBaseUserUrl() {
                return "https://www.openstreetmap.org/user";
            }

            @Override
            public String getJOSMWebsite() {
                return "https://josm.openstreetmap.de";
            }

            @Override
            public String getXMLBase() {
                return "https://josm.openstreetmap.de";
            }

            @Override
            public String getOSMWebsite() {
                return "https://www.openstreetmap.org";
            }

            @Override
            public String getOSMWiki() {
                return "https://wiki.openstreetmap.org";
            }

            @Override
            public String getDefaultOsmApiUrl() {
                return "https://api.openstreetmap.org";
            }
        });
        Config.setPreferencesInstance(new Preferences());
        TaggingPresetReader.setLoadIcons(false);
    }

    private static void validatePreset(Path file) throws Exception {
        try (var reader = Files.newBufferedReader(file)) {
            var entries = TaggingPresetReader.readAll(reader, false);
            if (entries.isEmpty()) {
                throw new IllegalStateException("JOSM did not load any preset entries");
            }
            System.out.println("JOSM loaded " + entries.size() + " preset entries.");
        }
    }

    private static void validateStyle(Path file) {
        var style = new MapCSSStyleSource(file.toUri().toString(), file.getFileName().toString(),
                file.getFileName().toString());
        style.loadStyleSource(false);
        if (!style.isValid() || !style.getErrors().isEmpty()) {
            style.getErrors().forEach(Throwable::printStackTrace);
            throw new IllegalStateException("JOSM found " + style.getErrors().size() + " style errors");
        }
        System.out.println("JOSM parsed the paint style.");
    }

    private static void validateValidator(Path file) throws Exception {
        var checker = new MapCSSTagChecker();
        var result = checker.addMapCSS(file.toUri().toString());
        if (result.parseChecks.isEmpty() || !result.parseErrors.isEmpty()) {
            result.parseErrors.forEach(Throwable::printStackTrace);
            throw new IllegalStateException("JOSM loaded " + result.parseChecks.size()
                    + " rules and found " + result.parseErrors.size() + " parse errors");
        }
        System.out.println("JOSM parsed " + result.parseChecks.size() + " validator rules.");
    }

    private static void validatePluginList(Path file) throws Exception {
        try (var input = Files.newInputStream(file)) {
            var plugins = new PluginListParser().parse(input);
            if (plugins.size() != 1) {
                throw new IllegalStateException("JOSM loaded " + plugins.size() + " plugin entries; expected one");
            }
            PluginInformation plugin = plugins.get(0);
            if (plugin.version == null || plugin.className == null || plugin.minjavaversion != 25) {
                throw new IllegalStateException(
                        "Plugin list is missing version/class metadata or the Java 25 minimum");
            }
            if (plugin.downloadlink == null || !plugin.downloadlink.startsWith("https://")) {
                throw new IllegalStateException("Plugin list download URL must be public HTTPS");
            }
            System.out.println("JOSM loaded plugin " + plugin.name + " version " + plugin.version
                    + " with Java " + plugin.minjavaversion + " minimum.");
        }
    }
}
