package dev.fandroid.client;

import dev.fandroid.FanDroidMod;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.resource.ResourcePackManager;

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
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Downloads a selected Google Fonts family into a generated client resource pack. */
public final class GoogleFontManager {
    private static final String NAMESPACE = "fandroid";
    private static final String PACK_DIRECTORY = "fandroid-google-font";
    private static final String PACK_ID = "file/" + PACK_DIRECTORY;
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 6.1; rv:3.5.1) Gecko/20090701 Firefox/3.5.1";
    private static final int MAX_FONT_BYTES = 32 * 1024 * 1024;
    private static final Pattern TTF_SOURCE = Pattern.compile(
            "src\\s*:\\s*url\\(([^)]+)\\)\\s*format\\(['\"]truetype['\"]\\)",
            Pattern.CASE_INSENSITIVE);
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
        if (css == null) return "";
        Matcher matcher = TTF_SOURCE.matcher(css);
        if (!matcher.find()) return "";
        String value = matcher.group(1).strip();
        if ((value.startsWith("\"") && value.endsWith("\""))
                || (value.startsWith("'") && value.endsWith("'"))) {
            value = value.substring(1, value.length() - 1);
        }
        return value;
    }

    private static Result preparePack(String family, int weight) {
        try {
            Path pack = packDirectory();
            Path fontFile = pack.resolve("assets").resolve(NAMESPACE).resolve("font").resolve("google.ttf");
            Path familyFile = pack.resolve(".relay-font-family");
            String marker = family + "\n" + weight;
            if (Files.isRegularFile(fontFile) && Files.isRegularFile(familyFile)
                    && marker.equals(Files.readString(familyFile, StandardCharsets.UTF_8).strip())) {
                writePackMetadata(pack, family, weight);
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
            String fontUrl = extractTtfUrl(cssResponse.body());
            if (fontUrl.isEmpty()) return Result.failure(family, "família ou variante regular não encontrada");
            URI fontUri = URI.create(fontUrl);
            String host = fontUri.getHost() == null ? "" : fontUri.getHost().toLowerCase(Locale.ROOT);
            if (!host.equals("fonts.gstatic.com") && !host.equals("themes.googleusercontent.com")) {
                return Result.failure(family, "origem de fonte recusada");
            }

            HttpRequest fontRequest = HttpRequest.newBuilder(fontUri)
                    .timeout(Duration.ofSeconds(30))
                    .header("User-Agent", USER_AGENT)
                    .GET().build();
            HttpResponse<byte[]> fontResponse = HTTP.send(fontRequest, HttpResponse.BodyHandlers.ofByteArray());
            byte[] bytes = fontResponse.body();
            if (fontResponse.statusCode() != 200) {
                return Result.failure(family, "arquivo da fonte HTTP " + fontResponse.statusCode());
            }
            if (bytes.length == 0 || bytes.length > MAX_FONT_BYTES || !isTrueType(bytes)) {
                return Result.failure(family, "arquivo TTF inválido");
            }

            Files.createDirectories(fontFile.getParent());
            Path temporary = fontFile.resolveSibling("google.ttf.tmp");
            Files.write(temporary, bytes);
            moveReplacing(temporary, fontFile);
            Files.writeString(familyFile, marker, StandardCharsets.UTF_8);
            writePackMetadata(pack, family, weight);
            return Result.success(family, family);
        } catch (Exception error) {
            FanDroidMod.LOGGER.warn("Could not prepare Google Font {}", family, error);
            return Result.failure(family, readableError(error));
        }
    }

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

    private static void writePackMetadata(Path pack, String family, int weight) throws Exception {
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
        Files.writeString(definition, """
                {
                  "providers": [
                    {
                      "type": "ttf",
                      "file": "%s:google.ttf",
                      "size": 11.0,
                      "oversample": 2.0,
                      "shift": [0.0, 0.0]
                    },
                    {
                      "type": "reference",
                      "id": "minecraft:default"
                    }
                  ]
                }
                """.formatted(NAMESPACE), StandardCharsets.UTF_8);
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
