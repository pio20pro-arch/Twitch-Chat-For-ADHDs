package pl.example.twitchbot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class EmoteCatalog implements AutoCloseable {
    private static final Pattern NON_WHITESPACE_TOKEN = Pattern.compile("\\S+");
    private static final long CATALOG_REFRESH_INTERVAL_MS = Duration.ofHours(6).toMillis();
    private static final int IMAGE_DOWNLOAD_THREADS = 100;
    private final String channelLogin;
    private final ObjectMapper json = new ObjectMapper();
    private final ExecutorService catalogExecutor = Executors.newFixedThreadPool(3, task -> {
        Thread thread = new Thread(task, "emote-catalog-loader");
        thread.setDaemon(true);
        return thread;
    });
    private final ExecutorService imageExecutor = Executors.newFixedThreadPool(IMAGE_DOWNLOAD_THREADS, task -> {
        Thread thread = new Thread(task, "emote-image-loader");
        thread.setDaemon(true);
        return thread;
    });
    private final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(6))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();
    private final Map<String, Emote> emotes = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<Path>> imageLoads = new ConcurrentHashMap<>();
    private final Path imageCacheDirectory = Path.of("cache", "emotes").toAbsolutePath().normalize();
    private final Path catalogIndexPath;
    private final AtomicBoolean channelLoading = new AtomicBoolean();
    private final AtomicBoolean channelLoaded = new AtomicBoolean();
    private volatile long channelRetryAfterMillis;
    private volatile Runnable catalogUpdatedAction = () -> {};

    EmoteCatalog(String channelLogin) {
        this.channelLogin = channelLogin;
        this.catalogIndexPath = imageCacheDirectory.resolve(
            "catalog-" + channelLogin.replaceAll("[^a-zA-Z0-9_-]", "_") + ".json"
        );
        boolean freshIndex = loadCatalogIndex();
        if (freshIndex) {
            channelLoaded.set(true);
        } else {
            CompletableFuture.runAsync(this::loadGlobals, catalogExecutor);
            CompletableFuture.runAsync(this::resolveAndLoadChannel, catalogExecutor);
        }
    }

    void loadChannelAsync(String twitchChannelId) {
        if (twitchChannelId == null || twitchChannelId.isBlank()
            || channelLoaded.get()
            || System.currentTimeMillis() < channelRetryAfterMillis
            || !channelLoading.compareAndSet(false, true)) {
            return;
        }
        CompletableFuture.runAsync(() -> {
            try {
                if (loadChannel(twitchChannelId)) {
                    channelLoaded.set(true);
                    saveCatalogIndex();
                } else {
                    channelRetryAfterMillis = System.currentTimeMillis() + CATALOG_REFRESH_INTERVAL_MS;
                }
            } finally {
                channelLoading.set(false);
            }
        }, catalogExecutor);
    }

    private void resolveAndLoadChannel() {
        try {
            JsonNode room = fetchJson("https://api.frankerfacez.com/v1/room/" + channelLogin).path("room");
            String twitchChannelId = room.path("twitch_id").asText("");
            if (twitchChannelId.isBlank()) {
                throw new IllegalStateException("FFZ did not return a Twitch channel ID");
            }
            loadChannelAsync(twitchChannelId);
        } catch (RuntimeException exception) {
            System.err.println(
                "Could not determine the Twitch channel ID for #" + channelLogin + ": " + exception.getMessage()
            );
        }
    }

    void setCatalogUpdatedAction(Runnable action) {
        catalogUpdatedAction = action == null ? () -> {} : action;
        if (!emotes.isEmpty()) catalogUpdatedAction.run();
    }

    void registerTwitchEmotes(String message, String emoteTag) {
        if (message == null || emoteTag == null || emoteTag.isBlank()) {
            return;
        }
        for (String group : emoteTag.split("/")) {
            int separator = group.indexOf(':');
            if (separator < 1 || separator == group.length() - 1) {
                continue;
            }
            String id = group.substring(0, separator);
            String firstPosition = group.substring(separator + 1).split(",", 2)[0];
            String[] bounds = firstPosition.split("-", 2);
            if (bounds.length != 2) {
                continue;
            }
            try {
                int start = Integer.parseInt(bounds[0]);
                int end = Integer.parseInt(bounds[1]);
                String code = substringByCodePoints(message, start, end + 1);
                if (!code.isBlank()) {
                    add(
                        code,
                        "https://static-cdn.jtvnw.net/emoticons/v2/" + id + "/default/dark/1.0",
                        "https://static-cdn.jtvnw.net/emoticons/v2/" + id + "/default/dark/2.0",
                        true,
                        200,
                        false
                    );
                }
            } catch (RuntimeException ignored) {
                // One malformed range must not stop the chat.
            }
        }
    }

    List<String> complete(String prefix) {
        if (prefix == null || prefix.isBlank()) {
            return List.of();
        }
        String normalized = prefix.toLowerCase(Locale.ROOT);
        return emotes.keySet().stream()
            .filter(code -> code.toLowerCase(Locale.ROOT).startsWith(normalized))
            .sorted(Comparator.comparingInt((String code) -> code.codePointCount(0, code.length()))
                .thenComparing(code -> code.toLowerCase(Locale.ROOT))
                .thenComparing(Comparator.naturalOrder()))
            .limit(100)
            .toList();
    }

    boolean contains(String code) {
        return code != null && emotes.containsKey(code);
    }

    EmoteInfo info(String code) {
        Emote emote = code == null ? null : emotes.get(code);
        return emote == null ? null : new EmoteInfo(
            emote.code(),
            emote.imageUrl(),
            emote.previewImageUrl(),
            emote.animated()
        );
    }

    List<EmoteRange> findRanges(String message) {
        if (message == null || message.isEmpty()) {
            return List.of();
        }
        List<EmoteRange> ranges = new ArrayList<>();
        Matcher matcher = NON_WHITESPACE_TOKEN.matcher(message);
        while (matcher.find()) {
            if (emotes.containsKey(matcher.group())) {
                ranges.add(new EmoteRange(matcher.start(), matcher.end()));
            }
        }
        return ranges;
    }

    boolean isEmoteOnly(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        Matcher matcher = NON_WHITESPACE_TOKEN.matcher(message);
        boolean found = false;
        while (matcher.find()) {
            found = true;
            String token = matcher.group();
            if (!emotes.containsKey(token) && !isUnicodeEmojiToken(token)) {
                return false;
            }
        }
        return found;
    }

    /** Recognizes phone/OS emoji without changing case-sensitive named-emote matching. */
    private static boolean isUnicodeEmojiToken(String token) {
        boolean foundEmojiBase = false;
        boolean foundKeycapBase = false;
        boolean foundKeycapMark = false;
        for (int offset = 0; offset < token.length();) {
            int codePoint = token.codePointAt(offset);
            if (isUnicodeEmojiBase(codePoint)) {
                foundEmojiBase = true;
            } else if (codePoint == '#' || codePoint == '*' || codePoint >= '0' && codePoint <= '9') {
                foundKeycapBase = true;
            } else if (codePoint == 0x20E3) {
                foundKeycapMark = true;
            } else if (!isUnicodeEmojiComponent(codePoint)) {
                return false;
            }
            offset += Character.charCount(codePoint);
        }
        return foundEmojiBase || foundKeycapBase && foundKeycapMark;
    }

    private static boolean isUnicodeEmojiBase(int codePoint) {
        return codePoint >= 0x1F000 && codePoint <= 0x1FAFF
            || codePoint >= 0x2600 && codePoint <= 0x27BF
            || codePoint >= 0x2300 && codePoint <= 0x23FF
            || codePoint >= 0x2B00 && codePoint <= 0x2BFF
            || codePoint == 0x00A9
            || codePoint == 0x00AE
            || codePoint == 0x203C
            || codePoint == 0x2049
            || codePoint == 0x2122
            || codePoint == 0x2139
            || codePoint >= 0x2194 && codePoint <= 0x2199
            || codePoint == 0x21A9
            || codePoint == 0x21AA
            || codePoint == 0x24C2
            || codePoint >= 0x25AA && codePoint <= 0x25AB
            || codePoint == 0x25B6
            || codePoint == 0x25C0
            || codePoint >= 0x25FB && codePoint <= 0x25FE
            || codePoint >= 0x2934 && codePoint <= 0x2935
            || codePoint == 0x3030
            || codePoint == 0x303D
            || codePoint == 0x3297
            || codePoint == 0x3299;
    }

    private static boolean isUnicodeEmojiComponent(int codePoint) {
        return codePoint == 0x200D // zero-width joiner
            || codePoint == 0xFE0E
            || codePoint == 0xFE0F
            || codePoint >= 0x1F3FB && codePoint <= 0x1F3FF // skin tones
            || codePoint >= 0xE0020 && codePoint <= 0xE007F; // subdivision-flag tags
    }

    int size() {
        return emotes.size();
    }

    List<EmoteInfo> sidebarEntries() {
        return emotes.values().stream()
            .filter(Emote::sidebarVisible)
            .filter(emote -> emote.imageUrl() != null && !emote.imageUrl().isBlank())
            .map(emote -> new EmoteInfo(
                emote.code(),
                emote.imageUrl(),
                emote.previewImageUrl(),
                emote.animated()
            ))
            .sorted(Comparator.comparing(EmoteInfo::code, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(EmoteInfo::code))
            .toList();
    }

    void loadImageAsync(EmoteInfo emote, Consumer<Path> onLoaded) {
        loadImageUrlAsync(emote, emote == null ? null : emote.imageUrl(), onLoaded, null);
    }

    void loadImageAsync(EmoteInfo emote, Consumer<Path> onLoaded, Runnable onFailed) {
        loadImageUrlAsync(emote, emote == null ? null : emote.imageUrl(), onLoaded, onFailed);
    }

    void loadPreviewImageAsync(EmoteInfo emote, Consumer<Path> onLoaded) {
        loadImageUrlAsync(emote, emote == null ? null : emote.previewImageUrl(), onLoaded, null);
    }

    private void loadImageUrlAsync(
        EmoteInfo emote,
        String url,
        Consumer<Path> onLoaded,
        Runnable onFailed
    ) {
        if (emote == null || url == null || url.isBlank() || onLoaded == null) return;
        CompletableFuture<Path> future = imageLoads.computeIfAbsent(
            url,
            ignored -> CompletableFuture.supplyAsync(() -> cachedImage(url), imageExecutor)
        );
        future.thenAccept(onLoaded).exceptionally(exception -> {
            imageLoads.remove(url, future);
            System.err.println("Could not load the image for emote " + emote.code() + ": " + exception.getMessage());
            if (onFailed != null) onFailed.run();
            return null;
        });
    }

    private void loadGlobals() {
        loadSafely("7TV global", () -> loadSevenTv(firstJson(
            "https://7tv.io/v3/emote-sets/global",
            "https://api.7tv.app/v3/emote-sets/global"
        ), 30));
        loadSafely("BTTV global", () -> loadBttv(fetchJson(
            "https://api.betterttv.net/3/cached/emotes/global"
        ), 20));
        loadSafely("FFZ global", () -> loadFfz(fetchJson(
            "https://api.frankerfacez.com/v1/set/global"
        ), 10));
        saveCatalogIndex();
        catalogUpdatedAction.run();
    }

    private boolean loadChannel(String twitchChannelId) {
        boolean sevenTvLoaded = loadSafely("7TV channel", () -> loadSevenTv(firstJson(
            "https://7tv.io/v3/users/twitch/" + twitchChannelId,
            "https://api.7tv.app/v3/users/twitch/" + twitchChannelId
        ), 130));
        boolean bttvLoaded = loadSafely("BTTV channel", () -> loadBttv(fetchJson(
            "https://api.betterttv.net/3/cached/users/twitch/" + twitchChannelId
        ), 120));
        boolean ffzLoaded = loadSafely("FFZ channel", () -> loadFfz(fetchJson(
            "https://api.frankerfacez.com/v1/room/" + channelLogin
        ), 110));
        catalogUpdatedAction.run();
        return sevenTvLoaded && bttvLoaded && ffzLoaded;
    }

    private void loadSevenTv(JsonNode root, int priority) {
        JsonNode list = root.path("emotes");
        if (!list.isArray()) {
            list = root.path("emote_set").path("emotes");
        }
        for (JsonNode item : list) {
            String name = text(item, "name");
            if (!name.isBlank()) {
                JsonNode data = item.path("data");
                JsonNode host = data.path("host");
                String baseUrl = normalizeImageUrl(text(host, "url"));
                String fileName = selectSevenTvFile(host.path("files"), "1x.webp", "2x.webp");
                String previewFileName = selectSevenTvFile(host.path("files"), "2x.webp", fileName);
                String imageUrl = baseUrl.isBlank() || fileName.isBlank()
                    ? ""
                    : baseUrl + "/" + fileName;
                String previewImageUrl = baseUrl.isBlank() || previewFileName.isBlank()
                    ? imageUrl
                    : baseUrl + "/" + previewFileName;
                add(name, imageUrl, previewImageUrl, data.path("animated").asBoolean(false), priority);
            }
        }
    }

    private void loadBttv(JsonNode root, int priority) {
        if (root.isArray()) {
            root.forEach(item -> addBttvItem(item, priority));
            return;
        }
        root.path("channelEmotes").forEach(item -> addBttvItem(item, priority));
        root.path("sharedEmotes").forEach(item -> addBttvItem(item, priority));
    }

    private void addBttvItem(JsonNode item, int priority) {
        String code = text(item, "code");
        if (!code.isBlank()) {
            String id = text(item, "id");
            add(
                code,
                id.isBlank() ? "" : "https://cdn.betterttv.net/emote/" + id + "/1x",
                id.isBlank() ? "" : "https://cdn.betterttv.net/emote/" + id + "/2x",
                item.path("animated").asBoolean(false),
                priority
            );
        }
    }

    private void loadFfz(JsonNode root, int priority) {
        JsonNode sets = root.path("sets");
        if (!sets.isObject()) {
            return;
        }
        sets.fields().forEachRemaining(entry -> entry.getValue().path("emoticons").forEach(item -> {
            String name = text(item, "name");
            if (!name.isBlank()) {
                JsonNode urls = item.path("urls");
                JsonNode animatedUrls = item.path("animated");
                boolean animated = animatedUrls.isObject();
                if (animated) urls = animatedUrls;
                String imageUrl = text(urls, "1");
                if (imageUrl.isBlank()) imageUrl = text(urls, "2");
                String previewImageUrl = text(urls, "2");
                if (previewImageUrl.isBlank()) previewImageUrl = imageUrl;
                add(
                    name,
                    normalizeImageUrl(imageUrl),
                    normalizeImageUrl(previewImageUrl),
                    animated,
                    priority
                );
            }
        }));
    }

    private void add(String code, int priority) {
        add(code, "", "", false, priority);
    }

    private void add(String code, String imageUrl, boolean animated, int priority) {
        add(code, imageUrl, imageUrl, animated, priority);
    }

    private void add(String code, String imageUrl, String previewImageUrl, boolean animated, int priority) {
        add(code, imageUrl, previewImageUrl, animated, priority, true);
    }

    private void add(
        String code,
        String imageUrl,
        String previewImageUrl,
        boolean animated,
        int priority,
        boolean sidebarVisible
    ) {
        Emote candidate = new Emote(code, imageUrl, previewImageUrl, animated, priority, sidebarVisible);
        emotes.compute(code, (ignored, current) -> {
            if (current == null) return candidate;
            Emote preferred = candidate.priority() >= current.priority() ? candidate : current;
            boolean visible = current.sidebarVisible() || candidate.sidebarVisible();
            if (preferred.sidebarVisible() == visible) return preferred;
            return new Emote(
                preferred.code(),
                preferred.imageUrl(),
                preferred.previewImageUrl(),
                preferred.animated(),
                preferred.priority(),
                visible
            );
        });
    }

    private boolean loadCatalogIndex() {
        if (!Files.isRegularFile(catalogIndexPath)) return false;
        try {
            JsonNode root = json.readTree(catalogIndexPath.toFile());
            JsonNode entries = root.path("emotes");
            if (!entries.isArray()) return false;
            for (JsonNode entry : entries) {
                String code = text(entry, "code");
                if (code.isBlank()) continue;
                int priority = entry.path("priority").asInt(0);
                add(
                    code,
                    text(entry, "imageUrl"),
                    text(entry, "previewImageUrl"),
                    entry.path("animated").asBoolean(false),
                    priority,
                    entry.has("sidebarVisible")
                        ? entry.path("sidebarVisible").asBoolean(true)
                        : priority != 200
                );
            }
            long age = System.currentTimeMillis() - root.path("savedAt").asLong(0L);
            return !emotes.isEmpty()
                && root.path("channelLoaded").asBoolean(false)
                && age >= 0
                && age <= CATALOG_REFRESH_INTERVAL_MS;
        } catch (Exception exception) {
            System.err.println("Could not load the emote index: " + exception.getMessage());
            return false;
        }
    }

    private synchronized void saveCatalogIndex() {
        if (emotes.isEmpty()) return;
        Path temporary = null;
        try {
            Files.createDirectories(imageCacheDirectory);
            var root = json.createObjectNode();
            root.put("savedAt", System.currentTimeMillis());
            root.put("channel", channelLogin);
            root.put("channelLoaded", channelLoaded.get());
            var entries = root.putArray("emotes");
            emotes.values().stream()
                .sorted(Comparator.comparing(Emote::code, String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(Emote::code))
                .forEach(emote -> {
                    var entry = entries.addObject();
                    entry.put("code", emote.code());
                    entry.put("imageUrl", emote.imageUrl());
                    entry.put("previewImageUrl", emote.previewImageUrl());
                    entry.put("animated", emote.animated());
                    entry.put("priority", emote.priority());
                    entry.put("sidebarVisible", emote.sidebarVisible());
                });
            temporary = Files.createTempFile(imageCacheDirectory, "catalog-", ".tmp");
            json.writeValue(temporary.toFile(), root);
            try {
                Files.move(
                    temporary,
                    catalogIndexPath,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                );
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, catalogIndexPath, StandardCopyOption.REPLACE_EXISTING);
            }
            temporary = null;
        } catch (Exception exception) {
            System.err.println("Could not save the emote index: " + exception.getMessage());
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (Exception ignored) {
                    // Temporary index will be replaced on a later save.
                }
            }
        }
    }

    private Path cachedImage(String url) {
        try {
            Files.createDirectories(imageCacheDirectory);
            String hash = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(url.getBytes(java.nio.charset.StandardCharsets.UTF_8))
            );
            Path cached = findCachedImage(hash);
            if (cached != null) return cached;

            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("User-Agent", "TwitchChatForADHDs/1.0")
                .GET()
                .build();
            HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("HTTP " + response.statusCode());
            }
            byte[] body = response.body();
            if (body.length == 0 || body.length > 8 * 1024 * 1024) {
                throw new IllegalStateException("Invalid image size: " + body.length);
            }
            String extension = imageExtension(
                response.headers().firstValue("Content-Type").orElse(""),
                body
            );
            cached = imageCacheDirectory.resolve(hash + extension);
            Path temporary = Files.createTempFile(imageCacheDirectory, hash + "-", ".tmp");
            try {
                Files.write(temporary, body);
                Files.move(temporary, cached, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(temporary);
            }
            return cached;
        } catch (Exception exception) {
            throw new IllegalStateException(exception.getMessage(), exception);
        }
    }

    private Path findCachedImage(String hash) throws Exception {
        for (String extension : List.of(".webp", ".gif", ".png", ".jpg", ".jpeg", ".img")) {
            Path candidate = imageCacheDirectory.resolve(hash + extension);
            if (!Files.isRegularFile(candidate) || Files.size(candidate) <= 0) continue;
            if (!extension.equals(".img")) return candidate;

            byte[] header = Files.readAllBytes(candidate);
            String detectedExtension = imageExtension("", header);
            Path renamed = imageCacheDirectory.resolve(hash + detectedExtension);
            Files.move(candidate, renamed, StandardCopyOption.REPLACE_EXISTING);
            return renamed;
        }
        return null;
    }

    private static String imageExtension(String contentType, byte[] bytes) {
        String normalizedType = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        if (normalizedType.contains("webp") || isWebp(bytes)) return ".webp";
        if (normalizedType.contains("gif") || startsWith(bytes, "GIF87a") || startsWith(bytes, "GIF89a")) {
            return ".gif";
        }
        if (normalizedType.contains("png") || isPng(bytes)) return ".png";
        if (normalizedType.contains("jpeg") || normalizedType.contains("jpg") || isJpeg(bytes)) return ".jpg";
        return ".img";
    }

    private static boolean isWebp(byte[] bytes) {
        return bytes.length >= 12
            && startsWith(bytes, "RIFF")
            && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P';
    }

    private static boolean isPng(byte[] bytes) {
        return bytes.length >= 8
            && (bytes[0] & 0xff) == 0x89
            && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G';
    }

    private static boolean isJpeg(byte[] bytes) {
        return bytes.length >= 3
            && (bytes[0] & 0xff) == 0xff
            && (bytes[1] & 0xff) == 0xd8
            && (bytes[2] & 0xff) == 0xff;
    }

    private static boolean startsWith(byte[] bytes, String value) {
        if (bytes.length < value.length()) return false;
        for (int index = 0; index < value.length(); index++) {
            if (bytes[index] != (byte) value.charAt(index)) return false;
        }
        return true;
    }

    private static String selectSevenTvFile(JsonNode files, String preferred, String fallbackName) {
        if (!files.isArray()) return "";
        String fallback = "";
        for (JsonNode file : files) {
            String name = text(file, "name");
            if (name.isBlank()) continue;
            if (fallback.isBlank()) fallback = name;
            if (name.equalsIgnoreCase(preferred)) return name;
            if (name.equalsIgnoreCase(fallbackName)) fallback = name;
        }
        return fallback;
    }

    private static String normalizeImageUrl(String url) {
        if (url == null || url.isBlank()) return "";
        if (url.startsWith("//")) return "https:" + url;
        if (url.startsWith("http://") || url.startsWith("https://")) return url;
        return "https://" + url;
    }

    private JsonNode firstJson(String... urls) {
        RuntimeException last = null;
        for (String url : urls) {
            try {
                return fetchJson(url);
            } catch (RuntimeException exception) {
                last = exception;
            }
        }
        throw last == null ? new IllegalStateException("No API URL was provided") : last;
    }

    private JsonNode fetchJson(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("User-Agent", "TwitchChatForADHDs/1.0")
                .GET()
                .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("HTTP " + response.statusCode());
            }
            return json.readTree(response.body());
        } catch (Exception exception) {
            throw new IllegalStateException(exception.getMessage(), exception);
        }
    }

    private boolean loadSafely(String source, Runnable loader) {
        try {
            loader.run();
            return true;
        } catch (RuntimeException exception) {
            System.err.println("Could not load emotes from " + source + ": " + exception.getMessage());
            return false;
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isTextual() ? value.asText() : "";
    }

    private static String substringByCodePoints(String value, int start, int end) {
        int count = value.codePointCount(0, value.length());
        if (start < 0 || end < start || end > count) {
            throw new IndexOutOfBoundsException();
        }
        int startOffset = value.offsetByCodePoints(0, start);
        int endOffset = value.offsetByCodePoints(0, end);
        return value.substring(startOffset, endOffset);
    }

    @Override
    public void close() {
        saveCatalogIndex();
        catalogExecutor.shutdownNow();
        imageExecutor.shutdownNow();
    }

    private record Emote(
        String code,
        String imageUrl,
        String previewImageUrl,
        boolean animated,
        int priority,
        boolean sidebarVisible
    ) {
    }

    record EmoteInfo(String code, String imageUrl, String previewImageUrl, boolean animated) {
    }

    record EmoteRange(int start, int end) {
    }
}
