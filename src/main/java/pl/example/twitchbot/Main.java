package pl.example.twitchbot;

import com.github.philippheuer.credentialmanager.domain.OAuth2Credential;
import com.github.twitch4j.TwitchClient;
import com.github.twitch4j.TwitchClientBuilder;
import com.github.twitch4j.chat.events.channel.ChannelMessageEvent;
import io.qt.core.QCoreApplication;
import io.qt.widgets.QApplication;

import java.io.IOException;
import java.io.Reader;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.StandardOpenOption;
import java.nio.file.StandardCopyOption;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

public final class Main {
    private static final String BOT_RESPONSE_STYLE = "\u001B[30;103m";
    private static final String CONSOLE_STYLE_RESET = "\u001B[0m";
    private static final int FAREWELL_BATCH_SIZE = 16;
    private static final Pattern ADVANCED_RULE_PREFIX = Pattern.compile("^\\{(\\d+)\\|(\\d+)\\|(\\d+)}(.*)$");
    private static final Pattern GREETED_USER_PROPERTY = Pattern.compile(
        "^\\s*greeted\\.user\\.([^=\\s]+)\\s*=\\s*true\\s*$",
        Pattern.CASE_INSENSITIVE
    );
    private static Instant nextResponseAt = Instant.EPOCH;
    private static FileChannel instanceLockChannel;
    private static FileLock instanceLock;
    private static Path instanceLockPath;

    private Main() {
    }

    public static void main(String[] args) throws Exception {
        QApplication.initialize(args);
        QCoreApplication.setApplicationName("Twitch Chat for ADHDs");
        QCoreApplication.setApplicationVersion("1.0");
        Path configPath = Path.of("config.properties").toAbsolutePath().normalize();
        Properties config = loadConfig(configPath);
        String channel = normalizeChannel(requiredSetting(config, "TWITCH_CHANNEL", "channel"));
        String botUsername = requiredSetting(config, "TWITCH_BOT_USERNAME", "bot.username")
            .toLowerCase(Locale.ROOT);
        if (!acquireInstanceLock(botUsername, channel)) {
            System.err.printf(
                "Another Twitch Chat for ADHDs instance is already running for #%s as %s.%n",
                channel,
                botUsername
            );
            QApplication.shutdown();
            return;
        }
        String token = requiredSetting(config, "TWITCH_OAUTH_TOKEN", "oauth.token")
            .replaceFirst("(?i)^oauth:", "");
        String clientId = config.getProperty("client.id", "").strip();
        boolean moderatorMode = onOffSetting(config, "mod.mode", false);
        boolean emoteColumnEnabled = onOffSetting(config, "emotes.column", true);
        boolean emoteAnimationEnabled = onOffSetting(config, "emotes.animation", true);
        boolean chatEmotesEnabled = onOffSetting(config, "emotes.chat", true);
        int emoteIconSize = Math.max(20, Math.min(64,
            Math.toIntExact(integerSetting(config, "emotes.icon.size", 32))));
        List<String> spellcheckLanguages = commaSeparatedSetting(config, "spellcheck.languages");
        List<String> grammarLanguages = commaSeparatedSetting(config, "grammar.languages");
        AtomicReference<ResponseSettings> responseSettings = new AtomicReference<>(loadResponseSettings(config));
        AtomicReference<ThreadViewer.ChatViewSettings> chatViewSettings = new AtomicReference<>(
            loadChatViewSettings(config)
        );
        AtomicReference<Set<String>> watchedUsers = new AtomicReference<>(
            loadUserSection(configPath, "watched.users")
        );
        AtomicReference<Set<String>> excludedUsers = new AtomicReference<>(
            loadUserSection(configPath, "excluded.users")
        );
        AtomicReference<Pattern> ownMentionPattern = new AtomicReference<>(
            buildOwnMentionPattern(botUsername, commaSeparatedSetting(config, "bot.mention.aliases"))
        );
        Path greetingConfigPath = Path.of(config.getProperty("greeting.config.file", "greetings.properties"))
            .toAbsolutePath()
            .normalize();
        AtomicReference<GreetingSettings> greetingSettings = new AtomicReference<>(
            loadGreetingSettings(greetingConfigPath)
        );
        Set<String> greetedUsers = ConcurrentHashMap.newKeySet();
        greetedUsers.addAll(greetingSettings.get().greetedUsers());
        Set<String> pendingGreetings = ConcurrentHashMap.newKeySet();
        AtomicBoolean greetingsEnabled = new AtomicBoolean(false);
        AtomicBoolean farewellInProgress = new AtomicBoolean(false);
        OccurrenceTracker occurrenceTracker = new OccurrenceTracker();
        Path rulesPath = (args.length > 0
            ? Path.of(args[0])
            : Path.of(config.getProperty("spammer.config.file", "chatbot-spammer.properties")))
            .toAbsolutePath()
            .normalize();
        AtomicReference<List<Rule>> rules = new AtomicReference<>(loadRules(rulesPath));
        WatchService rulesWatcher = startRulesWatcher(rulesPath, rules);

        OAuth2Credential credential = new OAuth2Credential("twitch", token);
        TwitchClientBuilder clientBuilder = TwitchClientBuilder.builder()
            .withEnableChat(true)
            .withChatAccount(credential)
            .withChatAutoJoinOwnChannel(false);
        if (moderatorMode && !clientId.isEmpty()) {
            clientBuilder.withClientId(clientId).withEnableHelix(true);
        }
        TwitchClient client = clientBuilder.build();
        AtomicInteger pendingResponses = new AtomicInteger();
        ConcurrentHashMap<String, Instant> sentResponses = new ConcurrentHashMap<>();
        ScheduledExecutorService responseExecutor = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "delayed-chat-responses");
            thread.setDaemon(true);
            return thread;
        });
        ExecutorService manualChatExecutor = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "manual-chat-sender");
            thread.setDaemon(true);
            return thread;
        });
        ScheduledExecutorService chattersExecutor = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "twitch-chatters-refresh");
            thread.setDaemon(true);
            return thread;
        });
        ExecutorService messageProcessor = new ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(4_000),
            task -> {
                Thread thread = new Thread(task, "incoming-message-processor");
                thread.setDaemon(true);
                return thread;
            },
            // Back-pressure is safer than losing a mention during an extreme burst.
            new ThreadPoolExecutor.CallerRunsPolicy()
        );
        EmoteCatalog emoteCatalog = new EmoteCatalog(channel);
        ThreadViewer threadViewer = new ThreadViewer(
            botUsername,
            channel,
            emoteCatalog,
            (parentMessageId, message, onSuccess) -> manualChatExecutor.execute(() -> {
                try {
                    if (client.getChat().sendMessage(channel, message, null, parentMessageId)) {
                        rememberSentResponse(sentResponses, message);
                        printBotResponse(channel, botUsername, message, 0);
                        onSuccess.run();
                    } else {
                        System.err.println("The message from the chat window was not sent.");
                    }
                } catch (RuntimeException exception) {
                    System.err.println("The message from the chat window was not sent: " + exception.getMessage());
                }
            }),
            () -> {
                boolean enabled = !greetingsEnabled.get();
                greetingsEnabled.set(enabled);
                return enabled;
            },
            chatViewSettings::get,
            excludedUsers::get,
            ownMentionPattern::get,
            emoteColumnEnabled,
            emoteAnimationEnabled,
            emoteIconSize,
            chatEmotesEnabled,
            spellcheckLanguages,
            grammarLanguages,
            moderatorMode,
            (onFinished, onSent) -> {
                greetingsEnabled.set(false);
                GreetingSettings current = greetingSettings.get();
                return scheduleFarewellBatches(
                    current.farewellMessages(),
                    greetedUsers,
                    excludedUsers.get(),
                    farewellInProgress,
                    responseSettings.get(),
                    client,
                    channel,
                    botUsername,
                    responseExecutor,
                    sentResponses,
                    greetingConfigPath,
                    onFinished,
                    onSent
                );
            }
        );
        WatchService greetingWatcher = startFileWatcher(
            greetingConfigPath,
            () -> reloadGreetingSettings(
                greetingConfigPath,
                greetingSettings,
                greetedUsers
            ),
            "greetings-file-watcher"
        );
        WatchService configWatcher = startFileWatcher(
            configPath,
            () -> reloadConfigSettings(
                configPath,
                responseSettings,
                chatViewSettings,
                watchedUsers,
                excludedUsers,
                botUsername,
                ownMentionPattern,
                threadViewer
            ),
            "config-file-watcher"
        );
        if (moderatorMode) {
            if (clientId.isEmpty()) {
                threadViewer.showChattersStatus("Set client.id in config.properties");
            } else {
                chattersExecutor.scheduleWithFixedDelay(
                    () -> refreshChatters(client, token, channel, botUsername, threadViewer),
                    0,
                    120,
                    TimeUnit.SECONDS
                );
            }
        }

        client.getEventManager().onEvent(ChannelMessageEvent.class, event -> {
            if (!event.getChannel().getName().equalsIgnoreCase(channel)) {
                return;
            }

            messageProcessor.execute(() -> {
            String author = event.getUser().getName();
            GreetingSettings currentGreetingSettings = greetingSettings.get();
            threadViewer.onMessage(
                event,
                excludedUsers.get(),
                watchedUsers.get()
            );
            String authenticatedUsername = credential.getUserName();
            boolean isBotUser = author.equalsIgnoreCase(botUsername)
                || (authenticatedUsername != null && author.equalsIgnoreCase(authenticatedUsername));
            if (isBotUser) {
                rememberSentResponse(sentResponses, event.getMessage());
                return; // The bot never replies to itself.
            }

            String normalizedAuthor = author.toLowerCase(Locale.ROOT);
            // Twitch replies belong exclusively in the thread window.
            // They do not trigger greetings, phrase counters, or automatic responses.
            if (event.getReplyInfo() != null) {
                return;
            }

            if (greetingsEnabled.get()
                && !excludedUsers.get().contains(normalizedAuthor)
                && (currentGreetingSettings.mode().equals("all")
                    || currentGreetingSettings.allowedUsers().contains(normalizedAuthor))
                && !greetedUsers.contains(normalizedAuthor)
                && pendingGreetings.add(normalizedAuthor)) {
                ResponseSettings currentResponseSettings = responseSettings.get();
                scheduleGreeting(
                    channel,
                    author,
                    normalizedAuthor,
                    botUsername,
                    currentGreetingSettings.messages(),
                    greetedUsers,
                    pendingGreetings,
                    greetingConfigPath,
                    client,
                    responseExecutor,
                    currentResponseSettings,
                    sentResponses,
                    greeting -> threadViewer.showSentMessage("Greetings", greeting)
                );
                return;
            }

            for (Rule rule : rules.get()) {
                if (!rule.matches(event.getMessage())) {
                    continue;
                }
                if (!occurrenceTracker.recordAndReached(rule)) {
                    break;
                }

                String responseTemplate = rule.responses().get(
                    ThreadLocalRandom.current().nextInt(rule.responses().size())
                );
                String baseResponse = responseTemplate
                    .replace("{user}", author)
                    .replace("{message}", event.getMessage());
                int repetitions = ThreadLocalRandom.current().nextInt(1, rule.maximumRepetitions() + 1);
                String response = String.join(" ", Collections.nCopies(repetitions, baseResponse));
                ResponseSettings currentResponseSettings = responseSettings.get();
                if (wasRecentlySent(
                    response,
                    sentResponses,
                    currentResponseSettings.sameResponseCooldownMs()
                )) {
                    break;
                }

                // A matching message can be skipped randomly so the bot does not repeat every emote.
                if (ThreadLocalRandom.current().nextDouble() >= currentResponseSettings.chance()
                    || !pendingResponses.compareAndSet(0, 1)) {
                    break;
                }

                long randomDelay = ThreadLocalRandom.current().nextLong(
                    currentResponseSettings.minimumDelayMs(),
                    currentResponseSettings.maximumDelayMs() + 1
                );
                if (rule.occurrenceWindowMs() > 0 && randomDelay > rule.occurrenceWindowMs()) {
                    randomDelay -= rule.occurrenceWindowMs();
                }
                long scheduledDelay = planResponseDelay(
                    randomDelay,
                    currentResponseSettings.minimumIntervalMs(),
                    currentResponseSettings.maximumIntervalMs()
                );
                responseExecutor.schedule(() -> {
                    try {
                        if (client.getChat().sendMessage(channel, response)) {
                            rememberSentResponse(sentResponses, response);
                            printBotResponse(channel, botUsername, response, scheduledDelay);
                            threadViewer.showSentMessage("Chatbox Spammer", response);
                        } else {
                            System.err.println("The Chatbox Spammer message was not sent to Twitch.");
                        }
                    } finally {
                        pendingResponses.decrementAndGet();
                    }
                }, scheduledDelay, TimeUnit.MILLISECONDS);
                break; // At most one response per message.
            }
            });
        });

        AtomicBoolean resourcesClosed = new AtomicBoolean(false);
        Runnable closeResources = () -> {
            if (!resourcesClosed.compareAndSet(false, true)) {
                return;
            }
            System.out.println("\nShutting down the bot...");
            try {
                rulesWatcher.close();
                configWatcher.close();
                greetingWatcher.close();
            } catch (IOException ignored) {
                // The program is shutting down anyway.
            }
            responseExecutor.shutdownNow();
            chattersExecutor.shutdownNow();
            manualChatExecutor.shutdownNow();
            messageProcessor.shutdownNow();
            client.close();
            releaseInstanceLock();
        };
        Thread shutdownHook = new Thread(closeResources, "bot-shutdown");
        Runtime.getRuntime().addShutdownHook(shutdownHook);

        client.getChat().joinChannel(channel);
        threadViewer.showWindow();
        threadViewer.setConnected();
        System.out.printf("Ready: #%s, %d Chatbox Spammer rules.%n", channel, rules.get().size());
        int exitCode = QApplication.exec();
        threadViewer.close();
        closeResources.run();
        try {
            Runtime.getRuntime().removeShutdownHook(shutdownHook);
        } catch (IllegalStateException ignored) {
            // The JVM has already started shutting down.
        }
        QApplication.shutdown();
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    private static WatchService startRulesWatcher(
        Path rulesPath,
        AtomicReference<List<Rule>> rules
    ) throws IOException {
        Path directory = rulesPath.getParent();
        WatchService watcher = FileSystems.getDefault().newWatchService();
        directory.register(
            watcher,
            StandardWatchEventKinds.ENTRY_CREATE,
            StandardWatchEventKinds.ENTRY_MODIFY
        );

        Thread thread = new Thread(() -> {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    WatchKey key = watcher.take();
                    boolean rulesChanged = false;

                    for (WatchEvent<?> event : key.pollEvents()) {
                        Object context = event.context();
                        if (context instanceof Path changedFile
                            && changedFile.getFileName().equals(rulesPath.getFileName())) {
                            rulesChanged = true;
                        }
                    }

                    if (!key.reset()) {
                        break;
                    }

                    if (rulesChanged) {
                        reloadRules(rulesPath, rules);
                    }
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } catch (ClosedWatchServiceException ignored) {
                // Normal program shutdown.
            }
        }, "chatbox-spammer-file-watcher");
        thread.setDaemon(true);
        thread.start();
        return watcher;
    }

    private static WatchService startFileWatcher(
        Path watchedPath,
        Runnable reloadAction,
        String threadName
    ) throws IOException {
        WatchService watcher = FileSystems.getDefault().newWatchService();
        watchedPath.getParent().register(
            watcher,
            StandardWatchEventKinds.ENTRY_CREATE,
            StandardWatchEventKinds.ENTRY_MODIFY
        );

        Thread thread = new Thread(() -> {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    WatchKey key = watcher.take();
                    boolean changed = false;
                    for (WatchEvent<?> event : key.pollEvents()) {
                        Object context = event.context();
                        if (context instanceof Path changedFile
                            && changedFile.getFileName().equals(watchedPath.getFileName())) {
                            changed = true;
                        }
                    }
                    if (!key.reset()) {
                        break;
                    }
                    if (changed) {
                        Thread.sleep(100);
                        reloadAction.run();
                    }
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } catch (ClosedWatchServiceException ignored) {
                // Normal program shutdown.
            }
        }, threadName);
        thread.setDaemon(true);
        thread.start();
        return watcher;
    }

    private static void refreshChatters(
        TwitchClient client,
        String accessToken,
        String channel,
        String moderatorLogin,
        ThreadViewer threadViewer
    ) {
        try {
            List<String> requestedLogins = channel.equalsIgnoreCase(moderatorLogin)
                ? List.of(channel)
                : List.of(channel, moderatorLogin);
            var users = client.getHelix()
                .getUsers(accessToken, null, requestedLogins)
                .execute()
                .getUsers();
            String broadcasterId = users.stream()
                .filter(user -> channel.equalsIgnoreCase(user.getLogin()))
                .map(user -> user.getId())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Channel account was not found."));
            String moderatorId = users.stream()
                .filter(user -> moderatorLogin.equalsIgnoreCase(user.getLogin()))
                .map(user -> user.getId())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Authenticated account was not found."));

            List<String> chatters = new ArrayList<>();
            Set<String> seenCursors = new HashSet<>();
            String cursor = null;
            int total = 0;
            do {
                var page = client.getHelix()
                    .getChatters(accessToken, broadcasterId, moderatorId, 1_000, cursor)
                    .execute();
                if (page.getTotal() != null) total = page.getTotal();
                if (page.getChatters() != null) {
                    page.getChatters().forEach(chatter -> chatters.add(
                        chatter.getUserName() == null || chatter.getUserName().isBlank()
                            ? chatter.getUserLogin()
                            : chatter.getUserName()
                    ));
                }
                cursor = page.getPagination() == null ? null : page.getPagination().getCursor();
            } while (cursor != null && !cursor.isBlank() && seenCursors.add(cursor));

            chatters.removeIf(username -> username == null || username.isBlank());
            chatters.sort(String.CASE_INSENSITIVE_ORDER);
            threadViewer.showChatters(chatters, total > 0 ? total : chatters.size());
        } catch (RuntimeException exception) {
            System.err.println("Could not retrieve the chatter list from Twitch Helix: " + exception.getMessage());
            threadViewer.showChattersStatus(
                "Unavailable: moderator role and moderator:read:chatters are required"
            );
        }
    }

    private static void reloadConfigSettings(
        Path configPath,
        AtomicReference<ResponseSettings> responseSettings,
        AtomicReference<ThreadViewer.ChatViewSettings> chatViewSettings,
        AtomicReference<Set<String>> watchedUsers,
        AtomicReference<Set<String>> excludedUsers,
        String botUsername,
        AtomicReference<Pattern> ownMentionPattern,
        ThreadViewer threadViewer
    ) {
        try {
            Properties config = loadConfig(configPath);
            ResponseSettings updated = loadResponseSettings(config);
            ThreadViewer.ChatViewSettings updatedChatView = loadChatViewSettings(config);
            Set<String> updatedWatchedUsers = loadUserSection(configPath, "watched.users");
            Set<String> updatedExcludedUsers = loadUserSection(configPath, "excluded.users");
            List<String> updatedMentionAliases = commaSeparatedSetting(config, "bot.mention.aliases");
            responseSettings.set(updated);
            chatViewSettings.set(updatedChatView);
            watchedUsers.set(updatedWatchedUsers);
            excludedUsers.set(updatedExcludedUsers);
            ownMentionPattern.set(buildOwnMentionPattern(botUsername, updatedMentionAliases));
            threadViewer.refreshChatView();
            System.out.printf(
                "Reloaded Chatbox Spammer/Greetings: %.0f%%, delay %d-%d ms, "
                    + "shared interval %d-%d ms; filters: emotes=%s, Chinese=%s, Japanese=%s, "
                    + "Korean=%s, Latin=%s, text fragments=%d, minimum=%d characters; threads=%s; "
                    + "chat: %d pt, spacing %d px.%n",
                updated.chance() * 100,
                updated.minimumDelayMs(),
                updated.maximumDelayMs(),
                updated.minimumIntervalMs(),
                updated.maximumIntervalMs(),
                updatedChatView.removeEmoteOnly(),
                updatedChatView.removeChinese(),
                updatedChatView.removeJapanese(),
                updatedChatView.removeKorean(),
                updatedChatView.removeLatin(),
                updatedChatView.hiddenTexts().size(),
                updatedChatView.minimumMessageLength(),
                updatedChatView.threadAutoOpenMode(),
                updatedChatView.fontPointSize(),
                updatedChatView.lineSpacing()
            );
        } catch (Exception exception) {
            System.err.println(
                "Could not reload config.properties: " + exception.getMessage()
                    + ". The last valid configuration remains active."
            );
        }
    }

    private static ThreadViewer.ChatViewSettings loadChatViewSettings(Properties config) {
        List<String> filteredLanguages = commaSeparatedSetting(config, "chat.filter.languages").stream()
            .map(value -> value.toLowerCase(Locale.ROOT))
            .toList();
        boolean removeEmoteOnly = Boolean.parseBoolean(
            config.getProperty("chat.filter.emote.only", "false").strip()
        );
        boolean legacyRemoveChineseJapanese = Boolean.parseBoolean(
            config.getProperty("chat.filter.chinese.japanese", "false").strip()
        );
        boolean removeChinese = Boolean.parseBoolean(
            config.getProperty("chat.filter.chinese", Boolean.toString(legacyRemoveChineseJapanese)).strip()
        ) || filteredLanguages.stream().anyMatch(value -> languageFilterBase(value).equals("zh")
            || value.equals("chinese") || value.equals("han"));
        boolean removeJapanese = Boolean.parseBoolean(
            config.getProperty("chat.filter.japanese", Boolean.toString(legacyRemoveChineseJapanese)).strip()
        ) || filteredLanguages.stream().anyMatch(value -> languageFilterBase(value).equals("ja")
            || value.equals("japanese") || value.equals("kana"));
        boolean removeKorean = Boolean.parseBoolean(
            config.getProperty("chat.filter.korean", "false").strip()
        ) || filteredLanguages.stream().anyMatch(value -> languageFilterBase(value).equals("ko")
            || value.equals("korean") || value.equals("hangul"));
        boolean removeLatin = Boolean.parseBoolean(
            config.getProperty("chat.filter.latin", "false").strip()
        ) || filteredLanguages.stream().anyMatch(value -> value.equals("latin"));
        Set<Character.UnicodeScript> filteredScripts = filteredLanguageScripts(filteredLanguages);
        String threadAutoOpenMode = config.getProperty("thread.auto.open", "all")
            .strip()
            .toLowerCase(Locale.ROOT);
        if (!threadAutoOpenMode.equals("watched") && !threadAutoOpenMode.equals("all")) {
            throw new IllegalArgumentException("thread.auto.open must be either watched or all.");
        }
        List<String> hiddenTexts = commaSeparatedSetting(config, "chat.filter.contains").stream()
            .map(value -> value.toLowerCase(Locale.ROOT))
            .toList();
        int fontPointSize = Math.toIntExact(integerSetting(config, "chat.font.size", 11));
        int lineSpacing = Math.toIntExact(integerSetting(config, "chat.line.spacing", 2));
        int minimumMessageLength = Math.toIntExact(integerSetting(config, "chat.filter.min.length", 0));
        if (fontPointSize < 8 || fontPointSize > 24) {
            throw new IllegalArgumentException("chat.font.size must be a number from 8 to 24.");
        }
        if (lineSpacing < 0 || lineSpacing > 20) {
            throw new IllegalArgumentException("chat.line.spacing must be a number from 0 to 20.");
        }
        if (minimumMessageLength < 0 || minimumMessageLength > 100) {
            throw new IllegalArgumentException("chat.filter.min.length must be a number from 0 to 100.");
        }
        return new ThreadViewer.ChatViewSettings(
            removeEmoteOnly,
            removeChinese,
            removeJapanese,
            removeKorean,
            removeLatin,
            filteredScripts,
            threadAutoOpenMode,
            hiddenTexts,
            minimumMessageLength,
            fontPointSize,
            lineSpacing
        );
    }

    private static Set<Character.UnicodeScript> filteredLanguageScripts(List<String> filters) {
        Set<Character.UnicodeScript> scripts = new HashSet<>();
        for (String configured : filters) {
            String base = languageFilterBase(configured);
            Character.UnicodeScript script = switch (base) {
                case "zh", "ja", "ko" -> null;
                case "ar", "fa", "ur" -> Character.UnicodeScript.ARABIC;
                case "ru", "uk", "be", "bg", "mk" -> Character.UnicodeScript.CYRILLIC;
                case "el" -> Character.UnicodeScript.GREEK;
                case "he", "yi" -> Character.UnicodeScript.HEBREW;
                case "th" -> Character.UnicodeScript.THAI;
                case "hi", "mr", "ne" -> Character.UnicodeScript.DEVANAGARI;
                case "bn" -> Character.UnicodeScript.BENGALI;
                case "ta" -> Character.UnicodeScript.TAMIL;
                case "km" -> Character.UnicodeScript.KHMER;
                case "my" -> Character.UnicodeScript.MYANMAR;
                case "lo" -> Character.UnicodeScript.LAO;
                case "si" -> Character.UnicodeScript.SINHALA;
                case "te" -> Character.UnicodeScript.TELUGU;
                case "kn" -> Character.UnicodeScript.KANNADA;
                case "ml" -> Character.UnicodeScript.MALAYALAM;
                case "gu" -> Character.UnicodeScript.GUJARATI;
                case "pa" -> Character.UnicodeScript.GURMUKHI;
                case "ka" -> Character.UnicodeScript.GEORGIAN;
                case "hy" -> Character.UnicodeScript.ARMENIAN;
                case "am", "ti" -> Character.UnicodeScript.ETHIOPIC;
                case "en", "pl", "de", "fr", "es", "pt", "it", "nl", "da", "sv",
                     "is", "ca", "ro", "sk", "sl", "lt", "ga", "gl", "br", "ast",
                     "eo", "tl", "crh", "latin" -> Character.UnicodeScript.LATIN;
                case "chinese", "han", "japanese", "kana", "korean", "hangul" -> null;
                default -> unicodeScriptSetting(configured);
            };
            if (script != null) scripts.add(script);
        }
        return Set.copyOf(scripts);
    }

    private static Character.UnicodeScript unicodeScriptSetting(String value) {
        try {
            return Character.UnicodeScript.forName(value.replace('-', '_'));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                "Unknown language code or writing system in chat.filter.languages: " + value
            );
        }
    }

    private static String languageFilterBase(String value) {
        int separator = value.indexOf('-');
        int underscore = value.indexOf('_');
        if (separator < 0 || underscore >= 0 && underscore < separator) separator = underscore;
        return separator < 0 ? value : value.substring(0, separator);
    }

    private static List<String> commaSeparatedSetting(Properties config, String name) {
        String value = config.getProperty(name, "");
        if (value.isBlank()) {
            return List.of();
        }
        return Pattern.compile("[,;]")
            .splitAsStream(value)
            .map(String::strip)
            .filter(item -> !item.isEmpty())
            .toList();
    }

    private static Pattern buildOwnMentionPattern(String botUsername, List<String> aliases) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        if (botUsername != null && !botUsername.isBlank()) names.add(botUsername.strip());
        if (aliases != null) {
            aliases.stream()
                .map(String::strip)
                .filter(alias -> !alias.isEmpty())
                .forEach(names::add);
        }
        String alternatives = names.stream()
            .sorted(Comparator.comparingInt(String::length).reversed())
            .map(Pattern::quote)
            .collect(java.util.stream.Collectors.joining("|"));
        String boundary = "[\\p{L}\\p{N}_]";
        return Pattern.compile(
            "(?i)(?<!" + boundary + ")@?(?:" + alternatives + ")(?!" + boundary + ")"
        );
    }

    private static void reloadGreetingSettings(
        Path greetingConfigPath,
        AtomicReference<GreetingSettings> greetingSettings,
        Set<String> greetedUsers
    ) {
        try {
            GreetingSettings updated = loadGreetingSettings(greetingConfigPath);
            greetingSettings.set(updated);
            // Never forget users greeted by this running instance during an automatic reload.
            // Clearing here created a short race in which another message could schedule a duplicate greeting.
            greetedUsers.addAll(updated.greetedUsers());
            System.out.printf(
                "Reloaded user settings: command %s, mode %s, greetings %d, goodbyes %d, allowed users %d.%n",
                updated.toggleCommand(),
                updated.mode(),
                updated.messages().size(),
                updated.farewellMessages().size(),
                updated.allowedUsers().size()
            );
        } catch (Exception exception) {
            System.err.println(
                "Could not reload greetings.properties: " + exception.getMessage()
                    + ". The last valid configuration remains active."
            );
        }
    }

    private static synchronized boolean acquireInstanceLock(String botUsername, String channel) {
        String identity = (botUsername + "-" + channel)
            .toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9_.-]", "_");
        Path lockPath = Path.of(
            System.getProperty("java.io.tmpdir"),
            "twitch-chat-for-adhds-" + identity + ".lock"
        );
        try {
            FileChannel candidateChannel = FileChannel.open(
                lockPath,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE
            );
            FileLock candidateLock;
            try {
                candidateLock = candidateChannel.tryLock();
            } catch (OverlappingFileLockException exception) {
                candidateLock = null;
            }
            if (candidateLock == null) {
                candidateChannel.close();
                return false;
            }
            instanceLockChannel = candidateChannel;
            instanceLock = candidateLock;
            instanceLockPath = lockPath;
            return true;
        } catch (IOException exception) {
            System.err.println("Could not create the single-instance lock: " + exception.getMessage());
            return false;
        }
    }

    private static synchronized void releaseInstanceLock() {
        try {
            if (instanceLock != null && instanceLock.isValid()) instanceLock.release();
        } catch (IOException ignored) {
            // The operating system releases the lock when the process exits.
        }
        try {
            if (instanceLockChannel != null) instanceLockChannel.close();
        } catch (IOException ignored) {
            // The process is already shutting down.
        }
        try {
            if (instanceLockPath != null) Files.deleteIfExists(instanceLockPath);
        } catch (IOException ignored) {
            // A stale empty lock file is harmless; the OS lock determines ownership.
        }
        instanceLock = null;
        instanceLockChannel = null;
        instanceLockPath = null;
    }

    private static String scheduleFarewellBatches(
        List<String> farewellMessages,
        Set<String> greetedUsers,
        Set<String> excludedUsers,
        AtomicBoolean farewellInProgress,
        ResponseSettings settings,
        TwitchClient client,
        String channel,
        String botUsername,
        ScheduledExecutorService responseExecutor,
        ConcurrentHashMap<String, Instant> sentResponses,
        Path greetingConfigPath,
        Consumer<Boolean> afterFarewell,
        Consumer<String> onSent
    ) {
        if (farewellMessages.isEmpty()) {
            System.err.println("No goodbye messages are defined in farewell.messages.");
            afterFarewell.accept(false);
            return "No farewell messages are defined in farewell.messages.";
        }

        Set<String> excludedRemembered = greetedUsers.stream()
            .filter(username -> excludedUsers.contains(username.toLowerCase(Locale.ROOT)))
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (!excludedRemembered.isEmpty()) {
            greetedUsers.removeAll(excludedRemembered);
            removeGreetedUsers(greetingConfigPath, excludedRemembered);
        }

        List<String> users = greetedUsers.stream()
            .filter(username -> !excludedUsers.contains(username.toLowerCase(Locale.ROOT)))
            .sorted(String.CASE_INSENSITIVE_ORDER)
            .toList();
        if (users.isEmpty()) {
            System.out.println("No users are stored as greeted.user.*.");
            afterFarewell.accept(true);
            return "No users to say goodbye to.";
        }
        if (!farewellInProgress.compareAndSet(false, true)) {
            System.out.println("Goodbye messages are already being sent.");
            afterFarewell.accept(false);
            return "Farewell messages are already being sent.";
        }

        List<String> farewellTemplates = farewellMessages.stream()
            .map(String::strip)
            .filter(template -> !template.isEmpty())
            .distinct()
            .toList();
        int batchCount = (users.size() + FAREWELL_BATCH_SIZE - 1) / FAREWELL_BATCH_SIZE;
        AtomicInteger pendingBatches = new AtomicInteger(batchCount);
        AtomicBoolean allBatchesSent = new AtomicBoolean(true);
        System.out.printf(
            "Scheduled goodbye for %d users in %d messages (up to %d users per message).%n",
            users.size(),
            batchCount,
            FAREWELL_BATCH_SIZE
        );

        int previousTemplateIndex = -1;
        for (int start = 0; start < users.size(); start += FAREWELL_BATCH_SIZE) {
            int end = Math.min(start + FAREWELL_BATCH_SIZE, users.size());
            List<String> batchUsers = List.copyOf(users.subList(start, end));
            int templateIndex;
            if (farewellTemplates.size() == 1) {
                templateIndex = 0;
            } else if (previousTemplateIndex < 0) {
                templateIndex = ThreadLocalRandom.current().nextInt(farewellTemplates.size());
            } else {
                int candidate = ThreadLocalRandom.current().nextInt(farewellTemplates.size() - 1);
                templateIndex = candidate >= previousTemplateIndex ? candidate + 1 : candidate;
            }
            previousTemplateIndex = templateIndex;
            String template = farewellTemplates.get(templateIndex);
            String mentions = batchUsers.stream()
                .map(username -> "@" + username)
                .reduce((left, right) -> left + " " + right)
                .orElse("");
            String farewell = template.contains("{users}")
                ? template.replace("{users}", mentions)
                : template.strip() + " " + mentions;
            long scheduledDelay = planResponseDelay(
                0,
                settings.minimumIntervalMs(),
                settings.maximumIntervalMs()
            );

            try {
                responseExecutor.schedule(() -> {
                    try {
                        if (client.getChat().sendMessage(channel, farewell)) {
                            rememberSentResponse(sentResponses, farewell);
                            printBotResponse(channel, botUsername, farewell, scheduledDelay);
                            onSent.accept(farewell);
                            greetedUsers.removeAll(batchUsers);
                            removeGreetedUsers(greetingConfigPath, Set.copyOf(batchUsers));
                        } else {
                            allBatchesSent.set(false);
                            System.err.println("The goodbye message was not sent to Twitch.");
                        }
                    } catch (RuntimeException exception) {
                        allBatchesSent.set(false);
                        System.err.println("The goodbye message was not sent: " + exception.getMessage());
                    } finally {
                        if (pendingBatches.decrementAndGet() == 0) {
                            farewellInProgress.set(false);
                            afterFarewell.accept(allBatchesSent.get());
                        }
                    }
                }, scheduledDelay, TimeUnit.MILLISECONDS);
            } catch (RuntimeException exception) {
                allBatchesSent.set(false);
                if (pendingBatches.decrementAndGet() == 0) {
                    farewellInProgress.set(false);
                    afterFarewell.accept(false);
                }
                System.err.println("Could not schedule the goodbye message: " + exception.getMessage());
            }
        }
        return "Scheduled farewell for " + users.size() + " users in " + batchCount + " messages.";
    }

    private static synchronized void removeGreetedUsers(Path path, Set<String> usernames) {
        Set<String> normalized = usernames.stream()
            .map(username -> username.toLowerCase(Locale.ROOT))
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
        try {
            List<String> retained = Files.readAllLines(path, StandardCharsets.UTF_8).stream()
                .filter(line -> {
                    Matcher matcher = GREETED_USER_PROPERTY.matcher(line);
                    return !matcher.matches()
                        || !normalized.contains(matcher.group(1).toLowerCase(Locale.ROOT));
                })
                .toList();
            Path temporary = Files.createTempFile(path.getParent(), path.getFileName().toString(), ".tmp");
            try {
                Files.write(temporary, retained, StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING);
                try {
                    Files.move(
                        temporary,
                        path,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE
                    );
                } catch (AtomicMoveNotSupportedException ignored) {
                    Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (IOException exception) {
            System.err.println("Could not remove users who received a goodbye from " + path + ": " + exception.getMessage());
        }
    }

    private static void reloadRules(Path rulesPath, AtomicReference<List<Rule>> rules) {
        try {
            List<Rule> updatedRules = loadRules(rulesPath);
            rules.set(updatedRules);
            System.out.printf(
                "Reloaded Chatbox Spammer (%s). Trigger count: %d.%n",
                rulesPath.getFileName(),
                updatedRules.size()
            );
        } catch (Exception exception) {
            System.err.println(
                "Could not reload Chatbox Spammer (" + rulesPath.getFileName() + "): "
                    + exception.getMessage()
                    + ". The last valid version remains active."
            );
        }
    }

    private static synchronized long planResponseDelay(
        long randomDelayMs,
        long minimumIntervalMs,
        long maximumIntervalMs
    ) {
        Instant now = Instant.now();
        Instant randomlyDelayed = now.plusMillis(randomDelayMs);
        Instant scheduledAt = randomlyDelayed.isAfter(nextResponseAt) ? randomlyDelayed : nextResponseAt;
        long nextInterval = ThreadLocalRandom.current().nextLong(
            minimumIntervalMs,
            maximumIntervalMs + 1
        );
        nextResponseAt = scheduledAt.plusMillis(nextInterval);
        return Math.max(0, Duration.between(now, scheduledAt).toMillis());
    }

    private static boolean wasRecentlySent(
        String response,
        ConcurrentHashMap<String, Instant> sentResponses,
        long cooldownMs
    ) {
        Instant lastSent = sentResponses.get(responseKey(response));
        return lastSent != null && Duration.between(lastSent, Instant.now()).toMillis() < cooldownMs;
    }

    private static String responseKey(String response) {
        return response.strip().toLowerCase(Locale.ROOT);
    }

    private static void rememberSentResponse(
        ConcurrentHashMap<String, Instant> sentResponses,
        String response
    ) {
        Instant now = Instant.now();
        sentResponses.put(responseKey(response), now);
        if (sentResponses.size() <= 2_000) {
            return;
        }
        Instant oldestUseful = now.minus(Duration.ofHours(1));
        sentResponses.entrySet().removeIf(entry -> entry.getValue().isBefore(oldestUseful));
    }

    private static void printBotResponse(
        String channel,
        String botUsername,
        String response,
        long delayMs
    ) {
        String delay = delayMs > 0 ? " (delay " + delayMs + " ms)" : "";
        System.out.printf(
            "%s[%s] %s: %s%s%s%n",
            BOT_RESPONSE_STYLE,
            channel,
            botUsername,
            response,
            delay,
            CONSOLE_STYLE_RESET
        );
    }

    private static void scheduleGreeting(
        String channel,
        String author,
        String normalizedAuthor,
        String botUsername,
        List<String> greetingMessages,
        Set<String> greetedUsers,
        Set<String> pendingGreetings,
        Path greetedUsersPath,
        TwitchClient client,
        ScheduledExecutorService responseExecutor,
        ResponseSettings settings,
        ConcurrentHashMap<String, Instant> sentResponses,
        Consumer<String> onSent
    ) {
        if (greetingMessages.isEmpty()) {
            pendingGreetings.remove(normalizedAuthor);
            System.err.println("No greeting messages are defined.");
            return;
        }

        String template = greetingMessages.get(ThreadLocalRandom.current().nextInt(greetingMessages.size()));
        String greeting = template.replace("{user}", author);
        long randomDelay = ThreadLocalRandom.current().nextLong(
            settings.minimumDelayMs(),
            settings.maximumDelayMs() + 1
        );
        long scheduledDelay = planResponseDelay(
            randomDelay,
            settings.minimumIntervalMs(),
            settings.maximumIntervalMs()
        );

        responseExecutor.schedule(() -> {
            try {
                if (client.getChat().sendMessage(channel, greeting)) {
                    rememberSentResponse(sentResponses, greeting);
                    greetedUsers.add(normalizedAuthor);
                    appendGreetedUser(greetedUsersPath, normalizedAuthor);
                    printBotResponse(channel, botUsername, greeting, scheduledDelay);
                    onSent.accept(greeting);
                }
            } finally {
                pendingGreetings.remove(normalizedAuthor);
            }
        }, scheduledDelay, TimeUnit.MILLISECONDS);
    }

    private static synchronized void appendGreetedUser(Path path, String username) {
        try {
            List<String> lines = Files.exists(path)
                ? new ArrayList<>(Files.readAllLines(path, StandardCharsets.UTF_8))
                : new ArrayList<>();
            String normalizedUsername = username.toLowerCase(Locale.ROOT);
            boolean alreadyStored = lines.stream().anyMatch(line -> {
                Matcher matcher = GREETED_USER_PROPERTY.matcher(line);
                return matcher.matches()
                    && matcher.group(1).toLowerCase(Locale.ROOT).equals(normalizedUsername);
            });
            if (alreadyStored) return;

            lines.add("greeted.user." + username + "=true");
            Path temporary = Files.createTempFile(path.getParent(), path.getFileName().toString(), ".tmp");
            try {
                Files.write(temporary, lines, StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING);
                try {
                    Files.move(
                        temporary,
                        path,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE
                    );
                } catch (AtomicMoveNotSupportedException ignored) {
                    Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (IOException exception) {
            System.err.println("Could not save the user to " + path + ": " + exception.getMessage());
        }
    }

    private static ResponseSettings loadResponseSettings(Properties config) {
        double chance = decimalSetting(config, "response.chance", 0.35);
        long minimumDelayMs = integerSetting(config, "response.delay.min.ms", 800);
        long maximumDelayMs = integerSetting(config, "response.delay.max.ms", 2500);
        long minimumIntervalMs = integerSetting(config, "response.interval.min.ms", 5000);
        long maximumIntervalMs = integerSetting(config, "response.interval.max.ms", 10000);
        long sameResponseCooldownMs = integerSetting(config, "response.same.cooldown.ms", 20000);

        if (chance < 0 || chance > 1) {
            throw new IllegalArgumentException("response.chance must be a number from 0 to 1.");
        }
        if (minimumDelayMs < 0 || maximumDelayMs < minimumDelayMs) {
            throw new IllegalArgumentException(
                "The delay must be non-negative, and response.delay.max.ms cannot be lower than the minimum."
            );
        }
        if (minimumIntervalMs < 0 || maximumIntervalMs < minimumIntervalMs) {
            throw new IllegalArgumentException(
                "The interval must be non-negative, and response.interval.max.ms cannot be lower than the minimum."
            );
        }
        if (sameResponseCooldownMs < 0) {
            throw new IllegalArgumentException("response.same.cooldown.ms cannot be negative.");
        }
        return new ResponseSettings(
            chance,
            minimumDelayMs,
            maximumDelayMs,
            minimumIntervalMs,
            maximumIntervalMs,
            sameResponseCooldownMs
        );
    }

    private static double decimalSetting(Properties config, String name, double defaultValue) {
        String value = config.getProperty(name);
        try {
            return value == null || value.isBlank() ? defaultValue : Double.parseDouble(value.strip());
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Setting " + name + " must be a number.");
        }
    }

    private static long integerSetting(Properties config, String name, long defaultValue) {
        String value = config.getProperty(name);
        try {
            return value == null || value.isBlank() ? defaultValue : Long.parseLong(value.strip());
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Setting " + name + " must be an integer.");
        }
    }

    private static boolean onOffSetting(Properties config, String name, boolean defaultValue) {
        String value = config.getProperty(name, defaultValue ? "on" : "off").strip().toLowerCase(Locale.ROOT);
        if (value.equals("on")) return true;
        if (value.equals("off")) return false;
        throw new IllegalArgumentException("Setting " + name + " must be either on or off.");
    }

    private static List<Rule> loadRules(Path path) throws IOException {
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException("Rules file not found: " + path.toAbsolutePath());
        }

        List<Rule> rules = new ArrayList<>();
        int lineNumber = 0;
        for (String rawLine : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            lineNumber++;
            String line = rawLine.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }

            int separator = line.indexOf('=');
            if (separator < 1 || separator == line.length() - 1) {
                throw new IllegalArgumentException(
                    "Invalid rule on line " + lineNumber + ". Use the format: word=response"
                );
            }

            String triggerDefinition = line.substring(0, separator).strip();
            String responseDefinition = line.substring(separator + 1).strip();
            List<String> responses = new ArrayList<>();
            for (String variant : responseDefinition.split("\\|", -1)) {
                String response = variant.strip();
                if (response.isEmpty()) {
                    throw new IllegalArgumentException(
                        "Empty response on line " + lineNumber
                            + ". Separate variants as: response1|response2"
                    );
                }
                responses.add(response);
            }
            int minimumOccurrences = 1;
            long occurrenceWindowMs = 0;
            int maximumRepetitions = 1;

            Matcher advanced = ADVANCED_RULE_PREFIX.matcher(triggerDefinition);
            String trigger = triggerDefinition;
            if (advanced.matches()) {
                minimumOccurrences = parsePositiveInteger(advanced.group(1), lineNumber, "occurrence count");
                occurrenceWindowMs = parsePositiveLong(advanced.group(2), lineNumber, "time window");
                maximumRepetitions = parsePositiveInteger(advanced.group(3), lineNumber, "repetition count");
                trigger = advanced.group(4).strip();
            }
            if (trigger.isEmpty()) {
                throw new IllegalArgumentException("Missing trigger phrase on line " + lineNumber + ".");
            }
            rules.add(new Rule(
                trigger,
                List.copyOf(responses),
                minimumOccurrences,
                occurrenceWindowMs,
                maximumRepetitions
            ));
        }

        if (rules.isEmpty()) {
            throw new IllegalArgumentException("File " + path.toAbsolutePath() + " contains no rules.");
        }
        return List.copyOf(rules);
    }

    private static int parsePositiveInteger(String value, int lineNumber, String description) {
        try {
            int result = Integer.parseInt(value);
            if (result < 1) {
                throw new NumberFormatException();
            }
            return result;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(
                "Invalid " + description + " on line " + lineNumber + ". A number greater than zero is required."
            );
        }
    }

    private static long parsePositiveLong(String value, int lineNumber, String description) {
        try {
            long result = Long.parseLong(value);
            if (result < 1) {
                throw new NumberFormatException();
            }
            return result;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(
                "Invalid " + description + " on line " + lineNumber + ". A number greater than zero is required."
            );
        }
    }

    private static List<String> propertyValues(Properties properties, String prefix) {
        return properties.stringPropertyNames().stream()
            .filter(name -> name.startsWith(prefix))
            .sorted()
            .map(properties::getProperty)
            .map(String::strip)
            .filter(value -> !value.isEmpty())
            .toList();
    }

    private static List<String> loadMultilineSection(Path path, String sectionName) throws IOException {
        List<String> result = new ArrayList<>();
        boolean insideSection = false;
        for (String rawLine : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            String line = rawLine.strip();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("!")) {
                continue;
            }
            int separator = line.indexOf('=');
            if (separator >= 0) {
                String key = line.substring(0, separator).strip();
                String inlineValue = line.substring(separator + 1).strip();
                insideSection = key.equals(sectionName);
                if (insideSection && !inlineValue.isEmpty()) {
                    if (sectionName.endsWith(".users")) {
                        Collections.addAll(result, inlineValue.split("[,\\s]+"));
                    } else {
                        result.add(inlineValue);
                    }
                }
                continue;
            }
            if (insideSection) {
                result.add(line);
            }
        }
        return List.copyOf(result);
    }

    private static Set<String> loadUserSection(Path path, String sectionName) throws IOException {
        return loadMultilineSection(path, sectionName).stream()
            .map(username -> username.toLowerCase(Locale.ROOT))
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private static Set<String> propertySuffixesWithTrueValue(Properties properties, String prefix) {
        Set<String> result = new HashSet<>();
        for (String name : properties.stringPropertyNames()) {
            if (name.startsWith(prefix)
                && Boolean.parseBoolean(properties.getProperty(name))
                && name.length() > prefix.length()) {
                result.add(name.substring(prefix.length()).toLowerCase(Locale.ROOT));
            }
        }
        return result;
    }

    private static Properties loadConfig(Path path) throws IOException {
        Properties config = new Properties();
        if (Files.isRegularFile(path)) {
            try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                config.load(reader);
            }
        }
        return config;
    }

    private static GreetingSettings loadGreetingSettings(Path path) throws IOException {
        Properties greetingConfig = loadConfig(path);
        String toggleCommand = greetingConfig.getProperty("toggle.command", "hi").strip();
        if (toggleCommand.isEmpty()) {
            throw new IllegalArgumentException("toggle.command cannot be empty.");
        }

        String mode = greetingConfig.getProperty("mode", "all").strip().toLowerCase(Locale.ROOT);
        if (!mode.equals("all") && !mode.equals("defined")) {
            throw new IllegalArgumentException("mode must be either all or defined.");
        }

        List<String> messages = new ArrayList<>(loadMultilineSection(path, "messages"));
        messages.addAll(propertyValues(greetingConfig, "message."));
        if (messages.isEmpty()) {
            throw new IllegalArgumentException("No messages are defined under messages=.");
        }

        List<String> farewellMessages = new ArrayList<>(loadMultilineSection(path, "farewell.messages"));
        farewellMessages.addAll(propertyValues(greetingConfig, "farewell.message."));
        if (farewellMessages.isEmpty()) {
            throw new IllegalArgumentException("No messages are defined under farewell.messages=.");
        }

        Set<String> allowedUsers = new HashSet<>();
        for (String username : loadMultilineSection(path, "allowed.users")) {
            allowedUsers.add(username.toLowerCase(Locale.ROOT));
        }
        String allowedUsersList = greetingConfig.getProperty("allowed.users", "");
        for (String username : allowedUsersList.split("[,\\s]+")) {
            if (!username.isBlank()) {
                allowedUsers.add(username.strip().toLowerCase(Locale.ROOT));
            }
        }
        for (String username : propertyValues(greetingConfig, "allowed.user.")) {
            allowedUsers.add(username.toLowerCase(Locale.ROOT));
        }

        Set<String> alreadyGreeted = propertySuffixesWithTrueValue(greetingConfig, "greeted.user.");
        return new GreetingSettings(
            toggleCommand,
            mode,
            List.copyOf(messages),
            List.copyOf(farewellMessages),
            Set.copyOf(allowedUsers),
            Set.copyOf(alreadyGreeted)
        );
    }

    private static String requiredSetting(Properties config, String environmentName, String propertyName) {
        String value = System.getenv(environmentName);
        if (value == null || value.isBlank()) {
            value = config.getProperty(propertyName);
        }
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                "Missing setting " + environmentName + " / " + propertyName + ". See README.md."
            );
        }
        return value.strip();
    }

    private static String normalizeChannel(String value) {
        String channel = value.strip().toLowerCase(Locale.ROOT);
        channel = channel.replaceFirst("^https?://(www\\.)?twitch\\.tv/", "");
        channel = channel.replaceFirst("^[#@]", "");

        int slash = channel.indexOf('/');
        if (slash >= 0) {
            channel = channel.substring(0, slash);
        }
        int query = channel.indexOf('?');
        if (query >= 0) {
            channel = channel.substring(0, query);
        }

        if (!channel.matches("[a-z0-9_]+")) {
            throw new IllegalArgumentException(
                "Invalid Twitch channel: " + value + ". Enter a name such as jinnytty or https://www.twitch.tv/jinnytty"
            );
        }
        return channel;
    }

    private record Rule(
        String trigger,
        List<String> responses,
        int minimumOccurrences,
        long occurrenceWindowMs,
        int maximumRepetitions
    ) {
        boolean matches(String message) {
            if (message == null || message.length() < trigger.length()) return false;
            int fromIndex = 0;
            while (fromIndex <= message.length() - trigger.length()) {
                int start = message.indexOf(trigger, fromIndex);
                if (start < 0) return false;
                int end = start + trigger.length();
                boolean validStart = start == 0 || !isRuleWordCharacter(message.codePointBefore(start));
                boolean validEnd = end == message.length()
                    || !isRuleWordCharacter(message.codePointAt(end));
                if (validStart && validEnd) return true;
                fromIndex = start + 1;
            }
            return false;
        }

        private static boolean isRuleWordCharacter(int codePoint) {
            if (codePoint == '_' || Character.isLetter(codePoint)) return true;
            int type = Character.getType(codePoint);
            return type == Character.DECIMAL_DIGIT_NUMBER
                || type == Character.LETTER_NUMBER
                || type == Character.OTHER_NUMBER;
        }
    }

    private static final class OccurrenceTracker {
        private final ConcurrentHashMap<String, Deque<Long>> occurrences = new ConcurrentHashMap<>();

        boolean recordAndReached(Rule rule) {
            if (rule.minimumOccurrences() <= 1) {
                return true;
            }

            long now = System.currentTimeMillis();
            String key = rule.minimumOccurrences() + "|" + rule.occurrenceWindowMs() + "|" + rule.trigger();
            Deque<Long> timestamps = occurrences.computeIfAbsent(key, ignored -> new ArrayDeque<>());
            synchronized (timestamps) {
                long oldestAllowed = now - rule.occurrenceWindowMs();
                while (!timestamps.isEmpty() && timestamps.peekFirst() < oldestAllowed) {
                    timestamps.removeFirst();
                }
                timestamps.addLast(now);
                if (timestamps.size() >= rule.minimumOccurrences()) {
                    timestamps.clear();
                    return true;
                }
                return false;
            }
        }
    }

    private record ResponseSettings(
        double chance,
        long minimumDelayMs,
        long maximumDelayMs,
        long minimumIntervalMs,
        long maximumIntervalMs,
        long sameResponseCooldownMs
    ) {
    }

    private record GreetingSettings(
        String toggleCommand,
        String mode,
        List<String> messages,
        List<String> farewellMessages,
        Set<String> allowedUsers,
        Set<String> greetedUsers
    ) {
    }
}
