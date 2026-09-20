package dev.fandroid.client;

import dev.fandroid.FanDroidMod;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.resource.ResourcePackManager;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Downloads a selected Google Fonts family into a generated client resource pack. */
public final class GoogleFontManager {
    private static final String NAMESPACE = "lyrica";
    private static final String PACK_DIRECTORY = "fandroid-google-font";
    private static final String PACK_ID = "file/" + PACK_DIRECTORY;
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 6.1; rv:3.5.1) Gecko/20090701 Firefox/3.5.1";
    private static final int FONT_PACK_REVISION = 2;
    static final float LOGICAL_FONT_SIZE = 11.0F;
    static final float HIGH_QUALITY_OVERSAMPLE = 5.0F;
    private static final int MAX_FONT_BYTES = 32 * 1024 * 1024;
    static final int MAX_TOTAL_FONT_BYTES = 48 * 1024 * 1024;
    static final int MAX_FONT_FILES = 12;
    private static final Pattern TTF_SOURCE = Pattern.compile(
            "url\\(\\s*([^)]+?)\\s*\\)\\s*format\\(['\"]truetype['\"]\\)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern GENERATED_FONT_FILE = Pattern.compile("google(?:-\\d+)?\\.ttf");
    private static final ExecutorService DOWNLOADS = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "Relay-Google-Fonts");
        thread.setDaemon(true);
        return thread;
    });
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private GoogleFontManager() { }

    public static CompletableFuture<Result> install(String requestedFamily) {
        return install(requestedFamily, 400);
    }

    public static CompletableFuture<Result> install(String requestedFamily, int requestedWeight) {
        String family = normalizeFamily(requestedFamily);
        int weight = normalizeWeight(requestedWeight);
        if (family.isEmpty()) {
            return CompletableFuture.completedFuture(Result.success("", "Minecraft"));
        }
        return CompletableFuture.supplyAsync(() -> preparePack(family, weight), DOWNLOADS)
                .thenCompose(prepared -> prepared.success()
                        ? enablePack(family)
                        : CompletableFuture.completedFuture(prepared))
                .exceptionally(error -> {
                    FanDroidMod.LOGGER.warn("Could not install Google Font {}", family, error);
                    return Result.failure(family, readableError(error));
                });
    }

    public static void restore(String family, Runnable fontChanged) {
        restore(family, 400, fontChanged);
    }

    public static void restore(String family, int weight, Runnable fontChanged) {
        String normalized = normalizeFamily(family);
        if (normalized.isEmpty()) return;
        install(normalized, weight).thenAccept(result -> {
            if (!result.success()) {
                FanDroidMod.LOGGER.warn("Could not restore Google Font {}: {}", normalized, result.message());
                return;
            }
            MinecraftClient client = MinecraftClient.getInstance();
            if (client != null && fontChanged != null) client.execute(fontChanged);
        });
    }

    public static String normalizeFamily(String value) {
        if (value == null) return "";
        String normalized = value.strip().replaceAll("\\s+", " ");
        if (normalized.length() > 80) normalized = normalized.substring(0, 80).stripTrailing();
        if (normalized.codePoints().anyMatch(codePoint -> !allowedFamilyCharacter(codePoint))) return "";
        return normalized;
    }

    static String extractTtfUrl(String css) {
        List<String> urls = extractTtfUrls(css);
        return urls.isEmpty() ? "" : urls.getFirst();
    }

    /** Collects all distinct TrueType subsets advertised by the Google Fonts stylesheet. */
    static List<String> extractTtfUrls(String css) {
        if (css == null || css.isBlank()) return List.of();
        Matcher matcher = TTF_SOURCE.matcher(css);
        LinkedHashSet<String> result = new LinkedHashSet<>();
        while (matcher.find()) {
            String value = matcher.group(1).strip();
            if ((value.startsWith("\"") && value.endsWith("\""))
                    || (value.startsWith("'") && value.endsWith("'"))) {
                value = value.substring(1, value.length() - 1);
            }
            if (!value.isBlank()) result.add(value);
        }
        return List.copyOf(result);
    }

    static List<URI> approvedFontUris(List<String> sources) {
        if (sources == null || sources.isEmpty()) {
            throw new IllegalArgumentException("nenhuma fonte TTF encontrada");
        }
        LinkedHashSet<URI> unique = new LinkedHashSet<>();
        for (String source : sources) {
            URI uri = URI.create(source);
            if (!isAllowedFontUri(uri)) throw new IllegalArgumentException("origem de fonte recusada");
            unique.add(uri);
        }
        if (unique.size() > MAX_FONT_FILES) {
            throw new IllegalArgumentException(
                    "muitos subconjuntos de fonte (limite " + MAX_FONT_FILES + ")");
        }
        return List.copyOf(unique);
    }

    static boolean isAllowedFontUri(URI uri) {
        if (uri == null || !"https".equalsIgnoreCase(uri.getScheme())) return false;
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        return host.equals("fonts.gstatic.com") || host.equals("themes.googleusercontent.com");
    }

    static boolean withinDownloadBudget(long downloadedBytes, long nextFontBytes) {
        return downloadedBytes >= 0 && nextFontBytes >= 0
                && downloadedBytes <= MAX_TOTAL_FONT_BYTES - nextFontBytes;
    }

    static String cacheMarker(String family, int weight) {
        return "v" + FONT_PACK_REVISION + "\n" + family + "\n" + normalizeWeight(weight);
    }

    private static Result preparePack(String family, int weight) {
        try {
            Path pack = packDirectory();
            Path fontDirectory = pack.resolve("assets").resolve(NAMESPACE).resolve("font");
            Path familyFile = pack.resolve(".relay-font-family");
            String marker = cacheMarker(family, weight);
            List<String> cachedFiles = generatedFontFiles(fontDirectory);
            if (!cachedFiles.isEmpty() && Files.isRegularFile(familyFile)
                    && marker.equals(Files.readString(familyFile, StandardCharsets.UTF_8).strip())) {
                writePackMetadata(pack, family, weight, cachedFiles);
                return Result.success(family, family);
            }

            URI cssUri = URI.create("https://fonts.googleapis.com/css2?family="
                    + URLEncoder.encode(family, StandardCharsets.UTF_8) + ":wght@" + weight + "&display=swap");
            HttpRequest cssRequest = HttpRequest.newBuilder(cssUri)
                    .timeout(Duration.ofSeconds(15))
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "text/css,*/*;q=0.1")
                    .GET().build();
            HttpResponse<String> cssResponse = HTTP.send(cssRequest,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (cssResponse.statusCode() != 200) {
                return Result.failure(family, "Google Fonts HTTP " + cssResponse.statusCode());
            }
            List<URI> sources = approvedFontUris(extractTtfUrls(cssResponse.body()));
            List<DownloadedFont> downloaded = downloadFonts(sources);
            Files.createDirectories(fontDirectory);
            List<String> installedFiles = installDownloadedFonts(fontDirectory, downloaded);
            removeObsoleteGeneratedFonts(fontDirectory, Set.copyOf(installedFiles));
            Files.writeString(familyFile, marker, StandardCharsets.UTF_8);
            writePackMetadata(pack, family, weight, installedFiles);
            return Result.success(family, family);
        } catch (Exception error) {
            FanDroidMod.LOGGER.warn("Could not prepare Google Font {}", family, error);
            return Result.failure(family, readableError(error));
        }
    }

    private static List<DownloadedFont> downloadFonts(List<URI> sources) throws Exception {
        List<DownloadedFont> downloaded = new ArrayList<>(sources.size());
        long totalBytes = 0;
        for (int index = 0; index < sources.size(); index++) {
            URI source = sources.get(index);
            HttpRequest request = HttpRequest.newBuilder(source)
                    .timeout(Duration.ofSeconds(30))
                    .header("User-Agent", USER_AGENT)
                    .GET().build();
            HttpResponse<byte[]> response = HTTP.send(request, HttpResponse.BodyHandlers.ofByteArray());
            byte[] bytes = response.body();
            if (response.statusCode() != 200) {
                throw new IOException("arquivo da fonte HTTP " + response.statusCode());
            }
            if (bytes.length == 0 || bytes.length > MAX_FONT_BYTES || !isTrueType(bytes)) {
                throw new IOException("arquivo TTF invalido");
            }
            if (!withinDownloadBudget(totalBytes, bytes.length)) {
                throw new IOException("limite total de fontes excedido");
            }
            totalBytes += bytes.length;
            downloaded.add(new DownloadedFont(String.format(Locale.ROOT, "google-%02d.ttf", index), bytes));
        }
        return List.copyOf(downloaded);
    }

    private static List<String> installDownloadedFonts(Path fontDirectory, List<DownloadedFont> fonts)
            throws Exception {
        List<String> files = new ArrayList<>(fonts.size());
        for (DownloadedFont font : fonts) {
            Path target = fontDirectory.resolve(font.fileName());
            Path temporary = target.resolveSibling(font.fileName() + ".tmp");
            Files.write(temporary, font.bytes());
            moveReplacing(temporary, target);
            files.add(font.fileName());
        }
        return List.copyOf(files);
    }

    private static List<String> generatedFontFiles(Path fontDirectory) throws IOException {
        if (!Files.isDirectory(fontDirectory)) return List.of();
        try (var files = Files.list(fontDirectory)) {
            return files.filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .filter(name -> GENERATED_FONT_FILE.matcher(name).matches())
                    .sorted()
                    .toList();
        }
    }

    private static void removeObsoleteGeneratedFonts(Path fontDirectory, Set<String> retained) throws IOException {
        try (var files = Files.list(fontDirectory)) {
            for (Path file : files.toList()) {
                String name = file.getFileName().toString();
                if (Files.isRegularFile(file) && GENERATED_FONT_FILE.matcher(name).matches()
                        && !retained.contains(name)) {
                    Files.deleteIfExists(file);
                }
            }
        }
    }

    private record DownloadedFont(String fileName, byte[] bytes) { }

    private static CompletableFuture<Result> enablePack(String family) {
        CompletableFuture<Result> result = new CompletableFuture<>();
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null) {
            result.complete(Result.failure(family, "Minecraft ainda não inicializado"));
            return result;
        }
        client.execute(() -> {
            try {
                ResourcePackManager manager = client.getResourcePackManager();
                manager.scanPacks();
                if (manager.getProfile(PACK_ID) == null) {
                    result.complete(Result.failure(family, "pacote de fonte não encontrado"));
                    return;
                }
                LinkedHashSet<String> enabled = new LinkedHashSet<>(manager.getEnabledIds());
                enabled.add(PACK_ID);
                manager.setEnabledProfiles(enabled);
                client.reloadResources().whenComplete((ignored, error) -> {
                    if (error == null) result.complete(Result.success(family, family));
                    else result.complete(Result.failure(family, readableError(error)));
                });
            } catch (Exception error) {
                result.complete(Result.failure(family, readableError(error)));
            }
        });
        return result;
    }

    private static void writePackMetadata(Path pack, String family, int weight, List<String> fontFiles)
            throws Exception {
        Files.createDirectories(pack);
        int packFormat = minecraftResourcePackFormat();
        String metadata = """
                {
                  "pack": {
                    "pack_format": %d,
                    "description": "Relay Google Font: %s (%d)"
                  }
                }
                """.formatted(packFormat, jsonEscape(family), weight);
        Files.writeString(pack.resolve("pack.mcmeta"), metadata, StandardCharsets.UTF_8);
        Path definition = pack.resolve("assets").resolve(NAMESPACE).resolve("font").resolve("google.json");
        Files.createDirectories(definition.getParent());
        Files.writeString(definition, fontDefinitionJson(fontFiles), StandardCharsets.UTF_8);
    }

    static String fontDefinitionJson(List<String> fontFiles) {
        if (fontFiles == null || fontFiles.isEmpty()) {
            throw new IllegalArgumentException("nenhum arquivo de fonte");
        }
        StringBuilder providers = new StringBuilder();
        for (String file : fontFiles) {
            if (!GENERATED_FONT_FILE.matcher(file).matches()) {
                throw new IllegalArgumentException("nome de arquivo de fonte invalido");
            }
            if (!providers.isEmpty()) providers.append(',');
            providers.append(String.format(Locale.ROOT, """
                    {
                      "type": "ttf",
                      "file": "%s:%s",
                      "size": %.1f,
                      "oversample": %.1f,
                      "shift": [0.0, 0.0]
                    }
                    """, NAMESPACE, file, LOGICAL_FONT_SIZE, HIGH_QUALITY_OVERSAMPLE));
        }
        providers.append("""
                ,
                {
                  "type": "reference",
                  "id": "minecraft:default"
                }
                """);
        return "{\n  \"providers\": [\n" + providers + "\n  ]\n}\n";
    }

    private static int minecraftResourcePackFormat() {
        String version = FabricLoader.getInstance().getModContainer("minecraft")
                .map(container -> container.getMetadata().getVersion().getFriendlyString())
                .orElse("1.21.1");
        return version.startsWith("1.21.11") ? 75 : 34;
    }

    private static Path packDirectory() {
        return FabricLoader.getInstance().getGameDir().resolve("resourcepacks").resolve(PACK_DIRECTORY);
    }

    private static boolean allowedFamilyCharacter(int codePoint) {
        return Character.isLetterOrDigit(codePoint) || Character.isWhitespace(codePoint)
                || codePoint == '-' || codePoint == '_' || codePoint == '\''
                || codePoint == '&' || codePoint == '.';
    }

    private static int normalizeWeight(int value) {
        if (value <= 400) return 400;
        if (value <= 500) return 500;
        if (value <= 600) return 600;
        return 700;
    }

    private static boolean isTrueType(byte[] bytes) {
        if (bytes.length < 4) return false;
        int signature = (bytes[0] & 0xFF) << 24 | (bytes[1] & 0xFF) << 16
                | (bytes[2] & 0xFF) << 8 | bytes[3] & 0xFF;
        return signature == 0x00010000 || signature == 0x74727565;
    }

    private static void moveReplacing(Path source, Path target) throws Exception {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String jsonEscape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String readableError(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) current = current.getCause();
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }

    public record Result(boolean success, String family, String message) {
        private static Result success(String family, String message) {
            return new Result(true, family, message);
        }

        private static Result failure(String family, String message) {
            return new Result(false, family, message);
        }
    }
}
