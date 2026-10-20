package pl.example.twitchbot;

import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import com.github.twitch4j.chat.events.channel.ChannelMessageEvent;
import com.github.twitch4j.common.util.ChatReply;

import io.qt.core.QModelIndex;
import io.qt.core.QEvent;
import io.qt.core.QPoint;
import io.qt.core.QSize;
import io.qt.core.QTimer;
import io.qt.core.Qt;
import io.qt.gui.QColor;
import io.qt.gui.QContextMenuEvent;
import io.qt.gui.QCursor;
import io.qt.gui.QFont;
import io.qt.gui.QFontMetrics;
import io.qt.gui.QIcon;
import io.qt.gui.QKeyEvent;
import io.qt.gui.QKeySequence;
import io.qt.gui.QMouseEvent;
import io.qt.gui.QMovie;
import io.qt.gui.QPaintEvent;
import io.qt.gui.QPainter;
import io.qt.gui.QPen;
import io.qt.gui.QPixmap;
import io.qt.gui.QResizeEvent;
import io.qt.gui.QShortcut;
import io.qt.gui.QTextCharFormat;
import io.qt.gui.QTextBlockFormat;
import io.qt.gui.QTextCursor;
import io.qt.gui.QTextDocument;
import io.qt.gui.QTextImageFormat;
import io.qt.gui.QWheelEvent;
import io.qt.widgets.QAbstractItemView;
import io.qt.widgets.QApplication;
import io.qt.widgets.QHBoxLayout;
import io.qt.widgets.QGridLayout;
import io.qt.widgets.QLabel;
import io.qt.widgets.QLayout;
import io.qt.widgets.QLineEdit;
import io.qt.widgets.QListView;
import io.qt.widgets.QListWidget;
import io.qt.widgets.QListWidgetItem;
import io.qt.widgets.QMainWindow;
import io.qt.widgets.QPushButton;
import io.qt.widgets.QPlainTextEdit;
import io.qt.widgets.QMenu;
import io.qt.widgets.QScrollBar;
import io.qt.widgets.QSizePolicy;
import io.qt.widgets.QSplitter;
import io.qt.widgets.QStackedWidget;
import io.qt.widgets.QStyleOptionViewItem;
import io.qt.widgets.QStyledItemDelegate;
import io.qt.widgets.QTextEdit;
import io.qt.widgets.QVBoxLayout;
import io.qt.widgets.QWidget;

/** Qt 6 window containing the complete chat and the existing watched/thread views. */
final class ThreadViewer implements AutoCloseable {
    private static final long CHAT_RETENTION_MS = Duration.ofMinutes(30).toMillis();
    private static final int MAX_CACHED_MESSAGES = 10_000;
    private static final int MAX_VISIBLE_CHAT_LINES = 1_500;
    private static final int MAX_PENDING_CHAT_LINES = 2_000;
    private static final int MAX_PENDING_UI_TASKS = 2_000;
    private static final int MAX_THREAD_TABS = 50;
    private static final int MAX_THREAD_LINES = 500;
    private static final int MAX_SEEN_IDS_PER_THREAD = 1_000;
    private static final int MAX_OPTIMISTIC_MESSAGES = 100;
    private static final int MAX_SENT_MESSAGE_HISTORY = 100;
    private static final int MAX_KNOWN_USERNAMES = 5_000;
    private static final int MAX_WATCHED_CHAT_LINES = 500;
    private static final int MAX_SENT_MESSAGE_LINES = 500;
    private static final int MAX_CHAT_LINES_PER_TICK = 24;
    private static final int MAX_UI_TASKS_PER_TICK = 40;
    private static final long UI_WORK_BUDGET_NANOS = 5_000_000L;
    private static final int CHAT_SCROLL_INTERVAL_MS = 16;
    private static final int CHAT_SCROLL_MAX_STEP_PX = 240;
    private static final double THREAD_TAB_WIDTH_SCALE = 0.80;
    private static final int HAN_SCRIPT = 1;
    private static final int JAPANESE_KANA_SCRIPT = 2;
    private static final int KOREAN_HANGUL_SCRIPT = 4;
    private static final int LATIN_SCRIPT = 8;
    private static final DateTimeFormatter MESSAGE_TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");

    @FunctionalInterface
    interface ReplySender {
        void send(String parentMessageId, String message, Runnable onSuccess);
    }

    @FunctionalInterface
    interface GreetingToggle {
        boolean toggle();
    }

    @FunctionalInterface
    interface FarewellStarter {
        String start(Consumer<Boolean> onFinished, Consumer<String> onSent);
    }

    private final ReplySender replySender;
    private final GreetingToggle greetingToggle;
    private final Supplier<ChatViewSettings> chatViewSettings;
    private final Supplier<Set<String>> excludedUsers;
    private final boolean emoteColumnEnabled;
    private final int emoteIconSize;
    private final boolean chatEmotesEnabled;
    private final FarewellStarter farewellStarter;
    private final String ownUsername;
    private final String normalizedOwnUsername;
    private final String channel;
    private final EmoteCatalog emotes;
    private final BilingualSpellChecker spellChecker;
    private final Supplier<Pattern> ownMentionPattern;
    private final Map<String, ChatLine> messages = Collections.synchronizedMap(new LinkedHashMap<>());
    private final Map<String, String> knownUsernames = new LinkedHashMap<>();
    private final Map<String, ThreadTab> threadTabs = new HashMap<>();
    private final Set<String> watchedMessageIds = new HashSet<>();
    private final ConcurrentLinkedQueue<PendingChatLine> pendingChatLines = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<Runnable> pendingCriticalUiTasks = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<Runnable> pendingUiTasks = new ConcurrentLinkedQueue<>();
    private final AtomicInteger pendingChatLineCount = new AtomicInteger();
    private final AtomicInteger pendingUiTaskCount = new AtomicInteger();
    private final IdentityHashMap<QListWidgetItem, ChatLine> chatItemLines = new IdentityHashMap<>();
    private final IdentityHashMap<ChatLine, QListWidgetItem> chatLineItems = new IdentityHashMap<>();
    private final IdentityHashMap<QListWidgetItem, ChatLine> watchedItemLines = new IdentityHashMap<>();
    private final Deque<ChatLine> recentChatLines = new ArrayDeque<>();
    private final List<String> sentMessageHistory = new ArrayList<>();

    private final QMainWindow window;
    private final WrappingTabWidget tabs;
    private final QListWidget chatList;
    private final QListWidget watchedList;
    private final QPlainTextEdit sentMessagesView;
    private final QPlainTextEdit chattersView;
    private final QLabel chattersHeading;
    private final QLabel chattersStatus;
    private QListWidget emotePickerList;
    private QLineEdit emoteSearchInput;
    private QLabel emotePopup;
    private QPushButton emoteAnimationButton;
    private QShortcut emoteSearchShortcut;
    private EmoteLineEdit publicChatInput;
    private EmoteLineEdit activeComposerInput;
    private final Map<String, EmoteCatalog.EmoteInfo> emotePickerEntries = new HashMap<>();
    private final Map<String, Path> emoteImagePaths = new HashMap<>();
    private final Map<String, QListWidgetItem> emotePickerItems = new HashMap<>();
    private final Set<String> emoteImageLoadsRequested = new HashSet<>();
    private final IdentityHashMap<QListWidgetItem, String> emotePickerCodes = new IdentityHashMap<>();
    private final Map<String, QIcon> emoteItemIcons = new HashMap<>();
    private final Map<String, QMovie> emoteItemMovies = new HashMap<>();
    private final Map<String, Path> chatEmoteImagePaths = new HashMap<>();
    private final Set<String> chatEmoteImageLoadsRequested = new HashSet<>();
    private final Map<String, QMovie> chatEmoteMovies = new HashMap<>();
    private boolean chatEmoteRefreshScheduled;
    private QMovie emotePopupMovie;
    private boolean emoteAnimationEnabled;
    private int emotePickerGeneration;
    private int emotePopupGeneration;
    private boolean emoteAnimationRefreshScheduled;
    private final QPushButton greetingButton;
    private final QPushButton farewellButton;
    private final QLabel statusLabel;
    private final QTimer chatScrollTimer;
    private final QTimer uiTimer;
    private final QTimer ageTimer;
    private ChatLineDelegate chatLineDelegate;
    private ChatLineDelegate watchedLineDelegate;
    private boolean chatAutoFollow = true;
    private boolean chatScrollStartScheduled;
    private boolean watchedScrollScheduled;
    private boolean closed;

    ThreadViewer(
        String ownUsername,
        String channel,
        EmoteCatalog emotes,
        ReplySender replySender,
        GreetingToggle greetingToggle,
        Supplier<ChatViewSettings> chatViewSettings,
        Supplier<Set<String>> excludedUsers,
        Supplier<Pattern> ownMentionPattern,
        boolean emoteColumnEnabled,
        boolean emoteAnimationEnabled,
        int emoteIconSize,
        boolean chatEmotesEnabled,
        List<String> spellcheckLanguages,
        List<String> grammarLanguages,
        boolean moderatorMode,
        FarewellStarter farewellStarter
    ) {
        this.ownUsername = ownUsername;
        this.channel = channel;
        this.emotes = emotes;
        this.replySender = replySender;
        this.greetingToggle = greetingToggle;
        this.chatViewSettings = chatViewSettings;
        this.excludedUsers = excludedUsers;
        this.ownMentionPattern = ownMentionPattern;
        this.emoteColumnEnabled = emoteColumnEnabled;
        this.emoteAnimationEnabled = emoteAnimationEnabled;
        this.emoteIconSize = emoteIconSize;
        this.chatEmotesEnabled = chatEmotesEnabled;
        this.spellChecker = new BilingualSpellChecker(spellcheckLanguages, grammarLanguages);
        this.farewellStarter = farewellStarter;
        this.normalizedOwnUsername = ownUsername.toLowerCase(Locale.ROOT);
        QApplication.setStyle("Fusion");
        window = new QMainWindow();
        window.setObjectName("mainWindow");
        window.setWindowTitle("Twitch Chat for ADHDs");
        window.setMinimumSize(1, 1);
        window.resize(1380, 820);

        QWidget central = new QWidget();
        central.setObjectName("central");
        central.setMinimumSize(0, 0);
        central.setSizePolicy(QSizePolicy.Policy.Ignored, QSizePolicy.Policy.Ignored);
        QVBoxLayout root = new QVBoxLayout(central);
        root.setSizeConstraint(QLayout.SizeConstraint.SetNoConstraint);
        root.setContentsMargins(3, 3, 3, 3);
        root.setSpacing(4);

        QWidget bottomBar = new QWidget();
        bottomBar.setObjectName("bottomBar");
        bottomBar.setMinimumWidth(0);
        bottomBar.setSizePolicy(QSizePolicy.Policy.Ignored, QSizePolicy.Policy.Fixed);
        QHBoxLayout header = new QHBoxLayout(bottomBar);
        header.setContentsMargins(0, 0, 0, 0);
        header.setSpacing(8);
        QLabel title = new QLabel("Twitch Chat for ADHDs");
        title.setObjectName("title");
        QLabel channelLabel = new QLabel("#" + channel);
        channelLabel.setObjectName("channelBadge");
        statusLabel = new QLabel("Connecting to Twitch…");
        statusLabel.setObjectName("status");
        greetingButton = new QPushButton("Greetings: OFF");
        greetingButton.setObjectName("greetingOff");
        farewellButton = new QPushButton("Goodbye: OFF");
        farewellButton.setObjectName("secondaryButton");
        allowHorizontalCompression(title);
        allowHorizontalCompression(channelLabel);
        allowHorizontalCompression(statusLabel);
        allowHorizontalCompression(greetingButton);
        allowHorizontalCompression(farewellButton);
        header.addWidget(title);
        header.addWidget(channelLabel);
        header.addStretch(1);
        header.addWidget(statusLabel);
        header.addWidget(greetingButton);
        header.addWidget(farewellButton);

        chatList = createMessageList(true);
        chatScrollTimer = new QTimer(window);
        chatScrollTimer.setInterval(CHAT_SCROLL_INTERVAL_MS);
        chatScrollTimer.timeout.connect(this::advanceSmoothChatScroll);
        configureChatScrollTracking();
        QWidget chatPanel = createChatPanel();
        QWidget emotePickerPanel = emoteColumnEnabled ? createEmotePickerPanel() : null;
        if (emoteSearchInput != null) {
            emoteSearchShortcut = new QShortcut(QKeySequence.StandardKey.Find, window);
            emoteSearchShortcut.setContext(Qt.ShortcutContext.WindowShortcut);
            emoteSearchShortcut.activated.connect(() -> {
                emoteSearchInput.setFocus();
                emoteSearchInput.selectAll();
            });
        }
        watchedList = createMessageList(false);
        sentMessagesView = createSentMessagesView();
        tabs = new WrappingTabWidget();
        tabs.setObjectName("threadTabs");
        tabs.addPermanentTab(createWatchedPanel(), "Watched");
        tabs.addPermanentTab(createSentMessagesPanel(), "Sent by me");
        QWidget chattersPanel;
        if (moderatorMode) {
            chattersView = createChattersView();
            chattersHeading = new QLabel("CHATTERS");
            chattersHeading.setObjectName("sectionTitle");
            chattersStatus = new QLabel("Waiting for Twitch Helix...");
            chattersStatus.setObjectName("hint");
            chattersPanel = createChattersPanel();
        } else {
            chattersView = null;
            chattersHeading = null;
            chattersStatus = null;
            chattersPanel = null;
        }

        QSplitter splitter = new QSplitter(Qt.Orientation.Horizontal);
        splitter.setChildrenCollapsible(true);
        splitter.setHandleWidth(3);
        int splitterIndex = 0;
        if (emoteColumnEnabled) {
            splitter.addWidget(emotePickerPanel);
            splitter.setStretchFactor(splitterIndex++, 2);
        }
        splitter.addWidget(chatPanel);
        splitter.setStretchFactor(splitterIndex++, 5);
        splitter.addWidget(tabs);
        splitter.setStretchFactor(splitterIndex++, 6);
        if (moderatorMode) {
            splitter.addWidget(chattersPanel);
            splitter.setStretchFactor(splitterIndex, 2);
        }
        if (emoteColumnEnabled && moderatorMode) {
            splitter.setSizes(List.of(220, 470, 500, 210));
        } else if (emoteColumnEnabled) {
            splitter.setSizes(List.of(230, 540, 610));
        } else if (moderatorMode) {
            splitter.setSizes(List.of(540, 610, 230));
        } else {
            splitter.setSizes(List.of(610, 750));
        }
        root.addWidget(splitter, 1);
        root.addWidget(bottomBar);
        window.setCentralWidget(central);
        window.layout().setSizeConstraint(QLayout.SizeConstraint.SetNoConstraint);
        applyStyleSheet();
        applyChatAppearance(chatList);
        applyChatAppearance(watchedList);

        chatList.itemClicked.connect(item -> openThread(chatItemLines.get(item)));
        watchedList.itemClicked.connect(item -> openThread(watchedItemLines.get(item)));
        tabs.setCurrentChangedHandler(index -> markSelectedThreadRead());
        tabs.setCloseRequestedHandler(this::closeTabAt);
        greetingButton.clicked.connect(this::toggleGreetings);
        farewellButton.clicked.connect(this::startFarewell);

        uiTimer = new QTimer(window);
        uiTimer.timeout.connect(this::drainUiWork);
        uiTimer.start(25);
        ageTimer = new QTimer(window);
        ageTimer.timeout.connect(this::updateTabAges);
        ageTimer.start(30_000);
        emotes.setCatalogUpdatedAction(() -> enqueueUi(() -> {
            clearMessageDocumentCaches();
            if (chatViewSettings.get().removeEmoteOnly()) applyChatFilter();
            if (emoteColumnEnabled) refreshEmotePicker();
        }));
    }

    void onMessage(
        ChannelMessageEvent event,
        Set<String> excludedUsers,
        Set<String> watchedUsers
    ) {
        emotes.loadChannelAsync(event.getChannel().getId());
        emotes.registerTwitchEmotes(event.getMessage(), event.getMessageEvent().getRawTagString("emotes"));
        String messageId = event.getMessageEvent().getMessageId().orElse(null);
        String author = event.getUser().getName();
        String displayAuthor = event.getMessageEvent().getUserDisplayName().orElse(author);
        rememberUsername(author, displayAuthor);
        ChatReply reply = event.getReplyInfo();
        String rootId = reply == null
            ? messageId
            : firstNonBlank(reply.getThreadMessageId(), reply.getMessageId(), messageId);
        String rootAuthor = reply == null
            ? author
            : firstNonBlank(reply.getThreadUserLogin(), reply.getUserLogin(), author);
        rememberUsername(rootAuthor, rootAuthor);
        ChatLine directParent = reply == null ? null : messages.get(reply.getMessageId());
        boolean addressedToOwnUser = !author.equalsIgnoreCase(ownUsername)
            && (mentionsOwnUsername(event.getMessage())
                || (reply != null && ownUsername.equalsIgnoreCase(rootAuthor))
                || (reply != null && ownUsername.equalsIgnoreCase(reply.getUserLogin()))
                || (directParent != null && directParent.ownMessage()));
        ChatLine current = new ChatLine(
            messageId,
            displayAuthor,
            event.getMessage(),
            System.currentTimeMillis(),
            rootId,
            rootAuthor,
            false,
            event.getMessageEvent().getUserChatColor().orElse(null),
            author.equalsIgnoreCase(ownUsername),
            addressedToOwnUser
        );
        if (messageId != null) {
            rememberMessage(current);
        }
        enqueueChatLine(current.withWatched(watchedUsers.contains(author.toLowerCase(Locale.ROOT))));

        if (reply == null) {
            if (messageId != null
                && !author.equalsIgnoreCase(ownUsername)
                && !isExcludedThreadUser(author, excludedUsers)
                && mentionsOwnUsername(event.getMessage())) {
                enqueueCriticalUi(() -> showMention(messageId, author, current));
            }
            return;
        }
        if (isExcludedThreadUser(author, excludedUsers)
            || isExcludedThreadUser(rootAuthor, excludedUsers)
            || rootId == null) {
            return;
        }
        ChatLine root = messages.get(rootId);
        if (root == null && rootId.equals(reply.getMessageId())) {
            root = new ChatLine(rootId, reply.getUserLogin(), reply.getMessageBody());
        }
        if (root != null && "StreamElements".equalsIgnoreCase(root.author())) {
            return;
        }
        ChatLine finalRoot = root;
        String autoOpenMode = chatViewSettings.get().threadAutoOpenMode();
        boolean involvesOwnUser = mentionsOwnUsername(current.message())
            || ownUsername.equalsIgnoreCase(rootAuthor)
            || ownUsername.equalsIgnoreCase(reply.getUserLogin())
            || (root != null && root.ownMessage())
            || (directParent != null && directParent.ownMessage());
        boolean createIfMissing = involvesOwnUser
            || autoOpenMode.equals("all")
            || watchedUsers.contains(author.toLowerCase(Locale.ROOT))
            || (rootAuthor != null && watchedUsers.contains(rootAuthor.toLowerCase(Locale.ROOT)));
        Runnable showReplyTask = () -> showReply(
            rootId,
            firstNonBlank(reply.getThreadUserLogin(), reply.getUserLogin(), "thread"),
            finalRoot,
            directParent,
            current,
            createIfMissing
        );
        if (involvesOwnUser) {
            enqueueCriticalUi(showReplyTask);
        } else {
            enqueueUi(showReplyTask);
        }
    }

    void showWindow() {
        window.show();
        window.raise();
        window.activateWindow();
    }

    void setConnected() {
        enqueueUi(() -> {
            statusLabel.setText("Connected • #" + channel);
            statusLabel.setStyleSheet("color:#72e6a6;font-weight:600;");
        });
    }

    void showSentMessage(String source, String message) {
        if (message == null || message.isBlank()) return;
        String safeSource = source == null || source.isBlank() ? "Manual" : source;
        enqueueCriticalUi(() -> {
            sentMessagesView.appendPlainText(
                "[" + LocalTime.now().format(MESSAGE_TIME_FORMAT) + "] [" + safeSource + "] " + message
            );
            QScrollBar scrollBar = sentMessagesView.verticalScrollBar();
            scrollBar.setValue(scrollBar.maximum());
        });
    }

    void showChatters(List<String> usernames, int total) {
        if (chattersView == null) return;
        List<String> snapshot = usernames == null ? List.of() : List.copyOf(usernames);
        enqueueCriticalUi(() -> {
            chattersHeading.setText("CHATTERS (" + total + ")");
            chattersStatus.setText("Connected users • refreshed now");
            chattersView.setPlainText(String.join("\n", snapshot));
            chattersView.moveCursor(QTextCursor.MoveOperation.Start);
        });
    }

    void showChattersStatus(String status) {
        if (chattersView == null) return;
        enqueueCriticalUi(() -> {
            chattersHeading.setText("CHATTERS");
            chattersStatus.setText(status == null ? "Unavailable" : status);
        });
    }

    void refreshChatView() {
        enqueueUi(() -> {
            applyChatAppearance(chatList);
            applyChatAppearance(watchedList);
            applyChatFilter();
        });
    }

    private QWidget createChatPanel() {
        QWidget panel = new QWidget();
        panel.setObjectName("panel");
        panel.setMinimumSize(0, 0);
        panel.setSizePolicy(QSizePolicy.Policy.Ignored, QSizePolicy.Policy.Expanding);
        QVBoxLayout layout = new QVBoxLayout(panel);
        layout.setContentsMargins(4, 4, 4, 4);
        layout.setSpacing(4);
        QLabel heading = new QLabel("FULL CHAT");
        heading.setObjectName("sectionTitle");
        heading.setToolTip("Click a message to open its thread • Right-click an emote to send it");
        layout.addWidget(heading);
        layout.addWidget(chatList, 1);
        QHBoxLayout composer = new QHBoxLayout();
        EmoteLineEdit input = new EmoteLineEdit();
        publicChatInput = input;
        activeComposerInput = input;
        input.enableSentMessageHistory();
        input.setPlaceholderText("Write a chat message…");
        input.setClearButtonEnabled(true);
        QPushButton send = new QPushButton("Send");
        input.setSubmitAction(() -> sendPublicMessage(input));
        send.clicked.connect(input::submit);
        composer.addWidget(input, 1);
        composer.addWidget(send);
        layout.addLayout(composer);
        return panel;
    }

    private QWidget createEmotePickerPanel() {
        QWidget panel = new QWidget();
        panel.setObjectName("panel");
        panel.setMinimumSize(0, 0);
        panel.setSizePolicy(QSizePolicy.Policy.Ignored, QSizePolicy.Policy.Expanding);
        QVBoxLayout layout = new QVBoxLayout(panel);
        layout.setContentsMargins(4, 4, 4, 4);
        layout.setSpacing(4);

        QLabel heading = new QLabel("EMOTES");
        heading.setObjectName("sectionTitle");
        emoteAnimationButton = new QPushButton();
        emoteAnimationButton.setObjectName("secondaryButton");
        updateEmoteAnimationButton();
        emoteAnimationButton.clicked.connect(this::toggleEmoteAnimation);

        emoteSearchInput = new QLineEdit();
        emoteSearchInput.setPlaceholderText("Search emotes…");
        emoteSearchInput.setClearButtonEnabled(true);
        emoteSearchInput.textChanged.connect(this::filterEmotePicker);

        emotePickerList = new EmotePickerList();
        emotePickerList.setObjectName("emotePicker");
        emotePickerList.setViewMode(QListView.ViewMode.IconMode);
        emotePickerList.setFlow(QListView.Flow.LeftToRight);
        emotePickerList.setWrapping(true);
        emotePickerList.setResizeMode(QListView.ResizeMode.Adjust);
        emotePickerList.setMovement(QListView.Movement.Static);
        emotePickerList.setIconSize(new QSize(emoteIconSize, emoteIconSize));
        emotePickerList.setGridSize(new QSize(emoteIconSize + 6, emoteIconSize + 6));
        emotePickerList.setWordWrap(false);
        emotePickerList.setTextElideMode(Qt.TextElideMode.ElideRight);
        emotePickerList.setUniformItemSizes(true);
        emotePickerList.setVerticalScrollMode(QAbstractItemView.ScrollMode.ScrollPerPixel);
        emotePickerList.setMouseTracking(true);
        emotePickerList.itemClicked.connect(this::selectEmoteFromPicker);
        emotePickerList.itemEntered.connect(this::showEmotePreviewForItem);
        emotePickerList.viewportEntered.connect(this::closeEmotePopup);
        emotePickerList.verticalScrollBar().valueChanged.connect(value -> {
            requestVisibleEmoteImages();
            requestVisibleEmoteAnimationRefresh();
        });

        layout.addWidget(heading);
        layout.addWidget(emoteAnimationButton);
        layout.addWidget(emoteSearchInput);
        layout.addWidget(emotePickerList, 1);
        return panel;
    }

    private void refreshEmotePicker() {
        if (emotePickerList == null) return;
        int generation = ++emotePickerGeneration;
        closeEmotePopup();
        stopAllEmoteItemMovies();
        clearEmoteItemIcons();
        emotePickerEntries.clear();
        emoteImagePaths.clear();
        emoteImageLoadsRequested.clear();
        emotePickerItems.clear();
        emotePickerCodes.clear();
        emotePickerList.clear();
        for (EmoteCatalog.EmoteInfo entry : emotes.sidebarEntries()) {
            emotePickerEntries.put(entry.code(), entry);
            QListWidgetItem item = new QListWidgetItem();
            item.setSizeHint(new QSize(emoteIconSize + 6, emoteIconSize + 6));
            item.setToolTip(entry.code());
            emotePickerList.addItem(item);
            emotePickerItems.put(entry.code(), item);
            emotePickerCodes.put(item, entry.code());
        }
        filterEmotePicker(emoteSearchInput == null ? "" : emoteSearchInput.text());
        QTimer.singleShot(0, () -> {
            if (generation != emotePickerGeneration) return;
            requestVisibleEmoteImages();
            requestVisibleEmoteAnimationRefresh();
        });
    }

    private void requestVisibleEmoteImages() {
        if (emotePickerList == null) return;
        var viewportRect = emotePickerList.viewport().rect();
        var preloadRect = viewportRect.adjusted(0, -viewportRect.height(), 0, viewportRect.height());
        try {
            for (Map.Entry<String, EmoteCatalog.EmoteInfo> entry : emotePickerEntries.entrySet()) {
                String code = entry.getKey();
                if (emoteImagePaths.containsKey(code) || emoteImageLoadsRequested.contains(code)) continue;
                QListWidgetItem item = emotePickerItems.get(code);
                if (item == null || item.isHidden()) continue;
                var itemRect = emotePickerList.visualItemRect(item);
                boolean shouldLoad = itemRect.isValid() && preloadRect.intersects(itemRect);
                itemRect.dispose();
                if (!shouldLoad) continue;

                emoteImageLoadsRequested.add(code);
                int generation = emotePickerGeneration;
                emotes.loadImageAsync(
                    entry.getValue(),
                    path -> enqueueUi(() -> {
                        if (generation != emotePickerGeneration || emotePickerList == null) return;
                        QListWidgetItem current = emotePickerItems.get(code);
                        if (current == null) return;
                        emoteImagePaths.put(code, path);
                        QPixmap pixmap = new QPixmap(path.toString());
                        if (pixmap.isNull()) {
                            current.setToolTip(code + " • image format could not be decoded");
                            pixmap.dispose();
                            return;
                        }
                        setEmoteItemIcon(code, current, new QIcon(pixmap));
                        pixmap.dispose();
                        if (entry.getValue().animated()) requestVisibleEmoteAnimationRefresh();
                    }),
                    () -> enqueueUi(() -> {
                        if (generation == emotePickerGeneration) emoteImageLoadsRequested.remove(code);
                    })
                );
            }
        } finally {
            preloadRect.dispose();
            viewportRect.dispose();
        }
    }

    private void selectEmoteFromPicker(QListWidgetItem item) {
        if (item == null) return;
        String selectedEmoteCode = emotePickerCodes.get(item);
        if (selectedEmoteCode == null) return;
        EmoteLineEdit target = activeComposerInput == null ? publicChatInput : activeComposerInput;
        if (target == null) return;
        String current = target.text();
        if (!current.isEmpty() && !Character.isWhitespace(current.charAt(current.length() - 1))) {
            target.insert(" ");
        }
        target.insert(selectedEmoteCode + " ");
        target.setFocus();
    }

    private void toggleEmoteAnimation() {
        emoteAnimationEnabled = !emoteAnimationEnabled;
        updateEmoteAnimationButton();
        closeEmotePopup();
        refreshChatEmoteAnimations();
        QTimer.singleShot(0, () -> {
            requestVisibleEmoteImages();
            requestVisibleEmoteAnimationRefresh();
        });
    }

    private void updateEmoteAnimationButton() {
        if (emoteAnimationButton != null) {
            emoteAnimationButton.setText("Animation: " + (emoteAnimationEnabled ? "ON" : "OFF"));
        }
    }

    private void filterEmotePicker(String query) {
        if (emotePickerList == null) return;
        String normalized = query == null ? "" : query.strip().toLowerCase(Locale.ROOT);
        for (Map.Entry<String, QListWidgetItem> entry : emotePickerItems.entrySet()) {
            entry.getValue().setHidden(!normalized.isEmpty()
                && !entry.getKey().toLowerCase(Locale.ROOT).contains(normalized));
        }
        requestVisibleEmoteAnimationRefresh();
    }

    private void showEmotePreviewForItem(QListWidgetItem item) {
        if (emotePickerList == null || item == null) return;
        String code = item == null ? null : emotePickerCodes.get(item);
        showEmotePreview(code);
    }

    private void showEmotePreview(String code) {
        EmoteCatalog.EmoteInfo entry = emotes.info(code);
        if (entry == null) return;

        closeEmotePopup();
        int popupGeneration = ++emotePopupGeneration;
        QLabel popup = new QLabel();
        popup.setObjectName("emotePopup");
        popup.setWindowFlag(Qt.WindowType.ToolTip, true);
        popup.setAlignment(Qt.AlignmentFlag.AlignCenter);
        popup.setMargin(6);
        popup.setStyleSheet("background:#171c24;border:1px solid #405066;border-radius:4px;");
        popup.setToolTip(code);
        popup.setText("Loading…");
        emotePopup = popup;

        QPoint globalPosition = QCursor.pos().add(new QPoint(16, 16));
        popup.adjustSize();
        popup.move(globalPosition);
        popup.show();
        emotes.loadPreviewImageAsync(entry, path -> enqueueUi(() -> {
            if (emotePopup != popup || popupGeneration != emotePopupGeneration) return;
            renderEmotePopup(popup, entry, path, globalPosition, popupGeneration);
        }));
    }

    private void renderEmotePopup(
        QLabel popup,
        EmoteCatalog.EmoteInfo entry,
        Path path,
        QPoint globalPosition,
        int popupGeneration
    ) {
        popup.clear();
        QPixmap pixmap = new QPixmap(path.toString());
        if (pixmap.isNull()) {
            pixmap.dispose();
            popup.setText("Preview unavailable");
        } else {
            popup.setPixmap(pixmap);
            pixmap.dispose();
        }
        popup.adjustSize();
        popup.move(globalPosition);
        popup.show();

        if (emoteAnimationEnabled && entry.animated()) {
            QMovie movie = new QMovie(path.toString());
            if (movie.isValid()) {
                emotePopupMovie = movie;
                movie.frameChanged.connect(frame -> {
                    if (emotePopup != popup || emotePopupMovie != movie) return;
                    QPixmap currentFrame = movie.currentPixmap();
                    if (!currentFrame.isNull()) {
                        popup.setPixmap(currentFrame);
                        popup.adjustSize();
                        popup.move(globalPosition);
                        popup.show();
                    }
                    currentFrame.dispose();
                });
                movie.start();
            } else {
                movie.dispose();
            }
        }
        QTimer.singleShot(4_000, () -> {
            if (emotePopup == popup && popupGeneration == emotePopupGeneration) closeEmotePopup();
        });
    }

    private void closeEmotePopup() {
        if (emotePopupMovie != null) {
            emotePopupMovie.stop();
            emotePopupMovie.dispose();
            emotePopupMovie = null;
        }
        if (emotePopup != null) {
            emotePopup.close();
            emotePopup.dispose();
            emotePopup = null;
        }
    }

    private void requestVisibleEmoteAnimationRefresh() {
        if (emotePickerList == null || emoteAnimationRefreshScheduled) return;
        emoteAnimationRefreshScheduled = true;
        QTimer.singleShot(0, () -> {
            emoteAnimationRefreshScheduled = false;
            refreshVisibleEmoteAnimations();
        });
    }

    private void refreshVisibleEmoteAnimations() {
        if (emotePickerList == null) return;
        if (!emoteAnimationEnabled) {
            stopAllEmoteItemMovies();
            return;
        }
        Set<String> shouldAnimate = new HashSet<>();
        var viewportRect = emotePickerList.viewport().rect();
        for (Map.Entry<String, EmoteCatalog.EmoteInfo> entry : emotePickerEntries.entrySet()) {
            if (!entry.getValue().animated() || !emoteImagePaths.containsKey(entry.getKey())) continue;
            QListWidgetItem item = emotePickerItems.get(entry.getKey());
            if (item != null && !item.isHidden()
                && viewportRect.intersects(emotePickerList.visualItemRect(item))) {
                shouldAnimate.add(entry.getKey());
            }
        }
        for (String code : new ArrayList<>(emoteItemMovies.keySet())) {
            if (!shouldAnimate.contains(code)) stopEmoteItemMovie(code);
        }
        for (String code : shouldAnimate) {
            if (!emoteItemMovies.containsKey(code)) startEmoteItemMovie(code);
        }
    }

    private void startEmoteItemMovie(String code) {
        Path path = emoteImagePaths.get(code);
        QListWidgetItem item = emotePickerItems.get(code);
        if (path == null || item == null) return;
        QMovie movie = new QMovie(path.toString());
        movie.frameChanged.connect(frame -> {
            if (emoteItemMovies.get(code) != movie || emotePickerItems.get(code) != item) return;
            setEmoteItemIcon(code, item, new QIcon(movie.currentPixmap()));
        });
        emoteItemMovies.put(code, movie);
        movie.start();
    }

    private void stopEmoteItemMovie(String code) {
        QMovie movie = emoteItemMovies.remove(code);
        if (movie == null) return;
        movie.stop();
        movie.dispose();
    }

    private void stopAllEmoteItemMovies() {
        for (String code : new ArrayList<>(emoteItemMovies.keySet())) stopEmoteItemMovie(code);
    }

    private void setEmoteItemIcon(String code, QListWidgetItem item, QIcon icon) {
        item.setIcon(icon);
        QIcon previous = emoteItemIcons.put(code, icon);
        if (previous != null) previous.dispose();
    }

    private void clearEmoteItemIcons() {
        for (QIcon icon : emoteItemIcons.values()) icon.dispose();
        emoteItemIcons.clear();
    }

    private QWidget createWatchedPanel() {
        QWidget panel = new QWidget();
        panel.setObjectName("panel");
        panel.setMinimumSize(0, 0);
        panel.setSizePolicy(QSizePolicy.Policy.Ignored, QSizePolicy.Policy.Expanding);
        QVBoxLayout layout = new QVBoxLayout(panel);
        layout.setContentsMargins(4, 4, 4, 4);
        layout.setSpacing(4);
        QLabel hint = new QLabel("Click a message to open its thread • Right-click an emote to send it");
        hint.setObjectName("hint");
        layout.addWidget(hint);
        layout.addWidget(watchedList, 1);
        return panel;
    }

    private QPlainTextEdit createSentMessagesView() {
        QPlainTextEdit view = new QPlainTextEdit();
        view.setObjectName("sentMessages");
        view.setReadOnly(true);
        view.setLineWrapMode(QPlainTextEdit.LineWrapMode.WidgetWidth);
        view.document().setMaximumBlockCount(MAX_SENT_MESSAGE_LINES);
        return view;
    }

    private QWidget createSentMessagesPanel() {
        QWidget panel = new QWidget();
        panel.setObjectName("panel");
        panel.setMinimumSize(0, 0);
        panel.setSizePolicy(QSizePolicy.Policy.Ignored, QSizePolicy.Policy.Expanding);
        QVBoxLayout layout = new QVBoxLayout(panel);
        layout.setContentsMargins(4, 4, 4, 4);
        layout.setSpacing(4);
        QLabel hint = new QLabel("Messages successfully sent from this account by the app");
        hint.setObjectName("hint");
        layout.addWidget(hint);
        layout.addWidget(sentMessagesView, 1);
        return panel;
    }

    private QPlainTextEdit createChattersView() {
        QPlainTextEdit view = new QPlainTextEdit();
        view.setObjectName("chattersView");
        view.setReadOnly(true);
        view.setLineWrapMode(QPlainTextEdit.LineWrapMode.NoWrap);
        return view;
    }

    private QWidget createChattersPanel() {
        QWidget panel = new QWidget();
        panel.setObjectName("panel");
        panel.setMinimumSize(0, 0);
        panel.setSizePolicy(QSizePolicy.Policy.Ignored, QSizePolicy.Policy.Expanding);
        QVBoxLayout layout = new QVBoxLayout(panel);
        layout.setContentsMargins(4, 4, 4, 4);
        layout.setSpacing(4);
        layout.addWidget(chattersHeading);
        layout.addWidget(chattersStatus);
        layout.addWidget(chattersView, 1);
        return panel;
    }

    private QListWidget createMessageList(boolean terminal) {
        QListWidget list = new EmoteMessageList(terminal);
        list.setObjectName(terminal ? "terminalChat" : "watchedChat");
        list.setUniformItemSizes(false);
        ChatViewSettings settings = chatViewSettings.get();
        list.setSpacing(settings.lineSpacing());
        list.setWordWrap(true);
        list.setResizeMode(QListView.ResizeMode.Adjust);
        list.setHorizontalScrollBarPolicy(Qt.ScrollBarPolicy.ScrollBarAlwaysOff);
        list.setVerticalScrollMode(QAbstractItemView.ScrollMode.ScrollPerPixel);
        list.setMouseTracking(true);
        list.viewport().setMouseTracking(true);
        QFont font = new QFont("Cascadia Mono");
        font.setStyleHint(QFont.StyleHint.Monospace);
        font.setPointSize(settings.fontPointSize());
        list.setFont(font);
        ChatLineDelegate delegate = new ChatLineDelegate(list, terminal);
        list.setItemDelegate(delegate);
        if (terminal) {
            chatLineDelegate = delegate;
        } else {
            watchedLineDelegate = delegate;
        }
        return list;
    }

    private void applyChatAppearance(QListWidget list) {
        ChatViewSettings settings = chatViewSettings.get();
        ChatLineDelegate delegate = list == chatList ? chatLineDelegate : watchedLineDelegate;
        if (delegate != null) delegate.clearCache();
        QFont font = list.font();
        font.setPointSize(settings.fontPointSize());
        list.setFont(font);
        list.setStyleSheet("font-size:" + settings.fontPointSize() + "pt;");
        list.setSpacing(settings.lineSpacing());
        list.doItemsLayout();
        list.viewport().update();
    }

    private void clearMessageDocumentCaches() {
        if (chatLineDelegate != null) chatLineDelegate.clearCache();
        if (watchedLineDelegate != null) watchedLineDelegate.clearCache();
    }

    private void toggleGreetings() {
        boolean enabled = greetingToggle.toggle();
        showGreetingState(enabled);
        window.statusBar().showMessage(
            "Automatic greetings have been turned " + (enabled ? "on." : "off."), 4_000
        );
    }

    private void showGreetingState(boolean enabled) {
        greetingButton.setText("Greetings: " + (enabled ? "ON" : "OFF"));
        greetingButton.setStyleSheet(enabled
            ? "background:#16885d;color:white;border:none;"
            : "background:#3a2430;color:#ffb5c5;border:none;");
    }

    private void startFarewell() {
        showGreetingState(false);
        showFarewellState(true);
        String status = farewellStarter.start(
            success -> enqueueUi(() -> {
                showFarewellState(false);
                window.statusBar().showMessage(
                    success ? "Goodbye messages sent." : "Goodbye finished with errors. Check the logs.",
                    8_000
                );
            }),
            message -> showSentMessage("Goodbye", message)
        );
        window.statusBar().showMessage(status, 8_000);
    }

    private void showFarewellState(boolean active) {
        farewellButton.setText("Goodbye: " + (active ? "ON" : "OFF"));
        farewellButton.setEnabled(!active);
        farewellButton.setStyleSheet(active
            ? "background:#16885d;color:white;border:none;"
            : "");
    }

    private static void allowHorizontalCompression(QWidget widget) {
        widget.setMinimumWidth(0);
        widget.setSizePolicy(QSizePolicy.Policy.Preferred, QSizePolicy.Policy.Preferred);
    }

    private void sendPublicMessage(EmoteLineEdit input) {
        String text = input.text().strip();
        if (!text.isEmpty()) {
            replySender.send(null, text, () -> {
                enqueueChatLine(new ChatLine(null, ownUsername, text, true));
                showSentMessage("Manual", text);
                enqueueUi(() -> {
                    rememberSentMessage(text);
                    input.resetHistoryNavigation();
                    clearSentText(input, text);
                });
            });
        }
    }

    private void rememberSentMessage(String message) {
        if (message == null || message.isBlank()) return;
        sentMessageHistory.add(message);
        while (sentMessageHistory.size() > MAX_SENT_MESSAGE_HISTORY) {
            sentMessageHistory.remove(0);
        }
    }

    private void sendPublicEmote(String emote) {
        replySender.send(null, emote, () -> {
            enqueueChatLine(new ChatLine(null, ownUsername, emote, true));
            showSentMessage("Manual", emote);
        });
    }

    private static void clearSentText(EmoteLineEdit input, String sentText) {
        if (input.text().strip().equals(sentText)) input.clear();
    }

    private void enqueueChatLine(ChatLine line) {
        // Filtering is pure message analysis, so keep it off the Qt event thread.
        pendingChatLines.add(new PendingChatLine(line, line.watched(), shouldHideFromChat(line)));
        int queued = pendingChatLineCount.incrementAndGet();
        while (queued > MAX_PENDING_CHAT_LINES) {
            if (pendingChatLines.poll() == null) break;
            queued = pendingChatLineCount.decrementAndGet();
        }
    }

    /** Important thread notifications are never discarded with cosmetic UI work. */
    private void enqueueCriticalUi(Runnable task) {
        pendingCriticalUiTasks.add(task);
    }

    private void enqueueUi(Runnable task) {
        pendingUiTasks.add(task);
        int queued = pendingUiTaskCount.incrementAndGet();
        while (queued > MAX_PENDING_UI_TASKS) {
            if (pendingUiTasks.poll() == null) break;
            queued = pendingUiTaskCount.decrementAndGet();
        }
    }

    private void drainUiWork() {
        if (closed) return;
        long deadline = System.nanoTime() + UI_WORK_BUDGET_NANOS;
        drainCriticalUiTasks(deadline);
        int processedUiTasks = drainNormalUiTasks(deadline, 8);
        flushChatLines(deadline);
        drainNormalUiTasks(deadline, MAX_UI_TASKS_PER_TICK - processedUiTasks);
    }

    private int drainNormalUiTasks(long deadline, int maximumTasks) {
        int processed = 0;
        Runnable task;
        while (processed < maximumTasks
            && System.nanoTime() < deadline
            && (task = pendingUiTasks.poll()) != null) {
            pendingUiTaskCount.decrementAndGet();
            runUiTask(task);
            processed++;
        }
        return processed;
    }

    private void drainCriticalUiTasks(long deadline) {
        Runnable task;
        int processed = 0;
        while (processed < MAX_UI_TASKS_PER_TICK
            && (processed == 0 || System.nanoTime() < deadline)
            && (task = pendingCriticalUiTasks.poll()) != null) {
            runUiTask(task);
            processed++;
        }
    }

    private void runUiTask(Runnable task) {
        try {
            task.run();
        } catch (RuntimeException exception) {
            window.statusBar().showMessage("View error: " + exception.getMessage(), 8_000);
        }
    }

    private void flushChatLines(long deadline) {
        if (pendingChatLineCount.get() <= 0 || System.nanoTime() >= deadline) return;
        boolean followLatest = chatAutoFollow;
        QScrollBar chatScrollBar = chatList.verticalScrollBar();
        int scrollPositionBeforeUpdate = chatScrollBar.value();
        boolean watchedChanged = false;
        boolean watchedUpdatesPaused = false;
        TrimResult trimResult = new TrimResult(false, 0);
        int processed = 0;
        PendingChatLine pending;
        chatList.setUpdatesEnabled(false);
        try {
            while (processed < MAX_CHAT_LINES_PER_TICK
                && System.nanoTime() < deadline
                && (pending = pendingChatLines.poll()) != null) {
                pendingChatLineCount.decrementAndGet();
                recentChatLines.addLast(pending.line());
                if (!pending.hidden()) addChatItem(pending.line());
                if (pending.watched() && pending.line().id() != null) {
                    if (!watchedUpdatesPaused) {
                        watchedList.setUpdatesEnabled(false);
                        watchedUpdatesPaused = true;
                    }
                    watchedChanged |= addWatchedItem(pending.line());
                }
                processed++;
            }
            trimResult = trimRecentChatLines();
        } finally {
            chatList.setUpdatesEnabled(true);
            chatList.viewport().update();
            if (watchedUpdatesPaused) {
                watchedList.setUpdatesEnabled(true);
                if (watchedChanged) watchedList.viewport().update();
            }
        }
        if (trimResult.removed() && trimResult.visiblePixels() > 0) {
            chatList.doItemsLayout();
            int anchoredPosition = Math.max(
                chatScrollBar.minimum(),
                Math.min(chatScrollBar.maximum(), scrollPositionBeforeUpdate - trimResult.visiblePixels())
            );
            chatScrollBar.setValue(anchoredPosition);
        }
        if (followLatest && chatList.count() > 0) {
            requestSmoothScrollToBottom();
        }
        if (watchedChanged) requestWatchedScrollToBottom();
    }

    private void addChatItem(ChatLine line) {
        QListWidgetItem item = new QListWidgetItem(line.author() + ": " + line.message());
        item.setToolTip("Click to open the thread for this message");
        if (line.watched()) {
            item.setBackground(new QColor("#183029"));
        } else if (ownUsername.equalsIgnoreCase(line.author())) {
            item.setBackground(new QColor("#32290f"));
        }
        chatItemLines.put(item, line);
        chatLineItems.put(line, item);
        chatList.addItem(item);
    }

    private void applyChatFilter() {
        boolean followLatest = chatAutoFollow;
        trimRecentChatLines();
        rebuildChatList();
        chatList.doItemsLayout();
        if (followLatest) requestSmoothScrollToBottom();
    }

    private void rebuildChatList() {
        if (chatLineDelegate != null) chatLineDelegate.clearCache();
        chatItemLines.clear();
        chatLineItems.clear();
        chatList.clear();
        for (ChatLine line : recentChatLines) {
            if (!shouldHideFromChat(line)) addChatItem(line);
        }
    }

    private TrimResult trimRecentChatLines() {
        boolean removed = false;
        int visiblePixels = 0;
        long cutoff = System.currentTimeMillis() - CHAT_RETENTION_MS;
        while (!recentChatLines.isEmpty() && recentChatLines.peekFirst().receivedAtMillis() < cutoff) {
            visiblePixels += removeOldestChatLine();
            removed = true;
        }
        while (recentChatLines.size() > MAX_VISIBLE_CHAT_LINES) {
            visiblePixels += removeOldestChatLine();
            removed = true;
        }
        return new TrimResult(removed, visiblePixels);
    }

    private int removeOldestChatLine() {
        ChatLine removedLine = recentChatLines.removeFirst();
        QListWidgetItem removedItem = chatLineItems.remove(removedLine);
        if (removedItem == null) return 0;
        int row = chatList.row(removedItem);
        int visiblePixels = row < 0
            ? 0
            : Math.max(0, chatList.sizeHintForRow(row)) + chatList.spacing();
        chatItemLines.remove(removedItem);
        if (chatLineDelegate != null) chatLineDelegate.removeCached(removedItem);
        if (row >= 0) chatList.takeItem(row);
        removedItem.dispose();
        return visiblePixels;
    }

    private boolean shouldHideFromChat(ChatLine line) {
        ChatViewSettings filters = chatViewSettings.get();
        String author = line.author() == null ? "" : line.author().toLowerCase(Locale.ROOT);
        if (line.ownMessage() || author.equals(normalizedOwnUsername)) {
            return false;
        }
        String rawMessage = line.message() == null ? "" : line.message().strip();
        String message = rawMessage.toLowerCase(Locale.ROOT);
        boolean detectScript = filters.removeChinese() || filters.removeJapanese()
            || filters.removeKorean() || filters.removeLatin() || !filters.filteredScripts().isEmpty();
        String detectionText = detectScript ? languageDetectionText(rawMessage) : "";
        int scripts = detectScript ? messageScriptFlags(detectionText) : 0;
        boolean containsJapanese = (scripts & JAPANESE_KANA_SCRIPT) != 0;
        boolean containsChinese = (scripts & HAN_SCRIPT) != 0 && !containsJapanese;
        boolean containsKorean = (scripts & KOREAN_HANGUL_SCRIPT) != 0;
        boolean containsLatin = (scripts & LATIN_SCRIPT) != 0;
        if (filters.removeEmoteOnly() && emotes.isEmoteOnly(line.message())) return true;
        if (filters.removeChinese() && containsChinese) return true;
        if (filters.removeJapanese() && containsJapanese) return true;
        if (filters.removeKorean() && containsKorean) return true;
        if (filters.removeLatin() && containsLatin) return true;
        if (!filters.filteredScripts().isEmpty()
            && containsFilteredScript(detectionText, filters.filteredScripts())) return true;
        if (excludedUsers.get().contains(author)) return true;
        for (String hiddenText : filters.hiddenTexts()) {
            if (message.contains(hiddenText)) return true;
        }
        return rawMessage.codePointCount(0, rawMessage.length()) < filters.minimumMessageLength();
    }

    private String languageDetectionText(String message) {
        StringBuilder result = new StringBuilder(message);
        List<EmoteCatalog.EmoteRange> ranges = emotes.findRanges(message);
        for (int index = ranges.size() - 1; index >= 0; index--) {
            EmoteCatalog.EmoteRange range = ranges.get(index);
            result.replace(range.start(), range.end(), " ");
        }
        return result.toString();
    }

    private static int messageScriptFlags(String message) {
        int flags = 0;
        for (int offset = 0; offset < message.length();) {
            int codePoint = message.codePointAt(offset);
            Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
            if (script == Character.UnicodeScript.HAN) {
                flags |= HAN_SCRIPT;
            } else if (script == Character.UnicodeScript.HIRAGANA
                || script == Character.UnicodeScript.KATAKANA) {
                flags |= JAPANESE_KANA_SCRIPT;
            } else if (script == Character.UnicodeScript.HANGUL) {
                flags |= KOREAN_HANGUL_SCRIPT;
            } else if (script == Character.UnicodeScript.LATIN) {
                flags |= LATIN_SCRIPT;
            }
            if (flags == (HAN_SCRIPT | JAPANESE_KANA_SCRIPT | KOREAN_HANGUL_SCRIPT | LATIN_SCRIPT)) {
                break;
            }
            offset += Character.charCount(codePoint);
        }
        return flags;
    }

    private static boolean containsFilteredScript(
        String message,
        Set<Character.UnicodeScript> filteredScripts
    ) {
        for (int offset = 0; offset < message.length();) {
            int codePoint = message.codePointAt(offset);
            if (filteredScripts.contains(Character.UnicodeScript.of(codePoint))) return true;
            offset += Character.charCount(codePoint);
        }
        return false;
    }

    private void configureChatScrollTracking() {
        QScrollBar scrollBar = chatList.verticalScrollBar();
        scrollBar.sliderPressed.connect(this::beginManualChatScroll);
        scrollBar.sliderReleased.connect(this::finishManualChatScroll);
        scrollBar.actionTriggered.connect(action -> {
            beginManualChatScroll();
            finishManualChatScroll();
        });
    }

    private void beginManualChatScroll() {
        chatAutoFollow = false;
        chatScrollTimer.stop();
    }

    private void finishManualChatScroll() {
        QTimer.singleShot(0, () -> {
            QScrollBar scrollBar = chatList.verticalScrollBar();
            chatAutoFollow = scrollBar.value() >= scrollBar.maximum() - 4;
            if (chatAutoFollow) requestSmoothScrollToBottom();
        });
    }

    private void requestSmoothScrollToBottom() {
        if (!chatAutoFollow || closed || chatScrollTimer.isActive() || chatScrollStartScheduled) return;
        chatScrollStartScheduled = true;
        QTimer.singleShot(0, () -> {
            chatScrollStartScheduled = false;
            if (!chatAutoFollow || closed || chatScrollTimer.isActive()) return;
            QScrollBar scrollBar = chatList.verticalScrollBar();
            if (scrollBar.value() < scrollBar.maximum()) chatScrollTimer.start();
        });
    }

    private void advanceSmoothChatScroll() {
        if (!chatAutoFollow || closed) {
            chatScrollTimer.stop();
            return;
        }
        QScrollBar scrollBar = chatList.verticalScrollBar();
        int current = scrollBar.value();
        int destination = scrollBar.maximum();
        int distance = destination - current;
        if (distance <= 0) {
            chatScrollTimer.stop();
            return;
        }

        // Ease toward a moving destination. New messages only move the target;
        // they never restart the animation, so the view cannot jump backwards.
        int step = Math.max(1, (distance + 4) / 5);
        step = Math.min(step, CHAT_SCROLL_MAX_STEP_PX);
        int next = Math.min(destination, current + step);
        scrollBar.setValue(next);
        if (next >= destination) chatScrollTimer.stop();
    }

    private boolean addWatchedItem(ChatLine line) {
        if (!watchedMessageIds.add(line.id())) return false;
        QListWidgetItem item = new QListWidgetItem(line.author() + ": " + line.message());
        item.setBackground(new QColor("#183029"));
        item.setToolTip("Click to open the thread for this message");
        watchedItemLines.put(item, line);
        watchedList.addItem(item);
        while (watchedList.count() > MAX_WATCHED_CHAT_LINES) {
            QListWidgetItem removed = watchedList.takeItem(0);
            ChatLine removedLine = watchedItemLines.remove(removed);
            if (removedLine != null && removedLine.id() != null) watchedMessageIds.remove(removedLine.id());
            if (watchedLineDelegate != null) watchedLineDelegate.removeCached(removed);
            removed.dispose();
        }
        return true;
    }

    private void requestWatchedScrollToBottom() {
        if (watchedScrollScheduled) return;
        watchedScrollScheduled = true;
        QTimer.singleShot(0, () -> {
            watchedScrollScheduled = false;
            if (closed) return;
            QScrollBar scrollBar = watchedList.verticalScrollBar();
            scrollBar.setValue(scrollBar.maximum());
        });
    }

    private void openThread(ChatLine line) {
        if (line == null || line.id() == null) return;
        String rootId = firstNonBlank(line.rootId(), line.id());
        ThreadTab tab = getOrCreateThread(rootId, firstNonBlank(line.rootAuthor(), line.author(), "thread"));
        tab.append(messages.get(rootId));
        tab.append(line);
        tab.selectReplyTarget(line.id());
        tabs.setCurrentWidget(tab.panel);
        QTimer.singleShot(0, tab::focusInput);
    }

    private void showReply(
        String rootId,
        String title,
        ChatLine root,
        ChatLine parent,
        ChatLine current,
        boolean createIfMissing
    ) {
        ThreadTab tab = threadTabs.get(rootId);
        if (tab == null && !createIfMissing) return;
        if (tab == null) tab = getOrCreateThread(rootId, title);
        tab.append(root);
        tab.append(parent);
        tab.append(current);
    }

    private void showMention(String messageId, String author, ChatLine current) {
        getOrCreateThread(messageId, author).append(current);
    }

    private ThreadTab getOrCreateThread(String rootId, String title) {
        ThreadTab existing = threadTabs.get(rootId);
        if (existing != null) return existing;
        enforceThreadTabLimit();
        ThreadTab created = createThreadTab(rootId, title);
        threadTabs.put(rootId, created);
        return created;
    }

    private ThreadTab createThreadTab(String rootId, String title) {
        QWidget panel = new QWidget();
        panel.setObjectName("panel");
        panel.setMinimumSize(0, 0);
        panel.setSizePolicy(QSizePolicy.Policy.Ignored, QSizePolicy.Policy.Expanding);
        QVBoxLayout layout = new QVBoxLayout(panel);
        layout.setContentsMargins(4, 4, 4, 4);
        layout.setSpacing(4);
        EmoteTranscript transcript = new EmoteTranscript();
        transcript.setObjectName("transcript");
        transcript.setReadOnly(true);
        transcript.setAcceptRichText(false);
        transcript.setToolTip("Click a message to select it as the reply target • Right-click an emote to send it");
        transcript.document().setMaximumBlockCount(MAX_THREAD_LINES);
        layout.addWidget(transcript, 1);
        QHBoxLayout composer = new QHBoxLayout();
        EmoteLineEdit input = new EmoteLineEdit();
        input.setPlaceholderText("Reply in thread…");
        input.setClearButtonEnabled(true);
        QPushButton send = new QPushButton("Reply");
        composer.addWidget(input, 1);
        composer.addWidget(send);
        layout.addLayout(composer);

        String tabTitle = shortTitle(title);
        int tabIndex = tabs.insertTab(2, panel, tabTitle);
        tabs.setTabToolTip(tabIndex, "Thread started by " + title);
        ThreadTab threadTab = new ThreadTab(rootId, panel, transcript, input, tabTitle);
        transcript.setReplyAction(threadTab::selectReplyTarget);
        transcript.setEmoteAction(emote -> {
            replySender.send(threadTab.replyTargetId(), emote, () -> {
                ChatLine ownLine = new ChatLine(null, ownUsername, emote, true);
                enqueueChatLine(ownLine);
                showSentMessage("Thread reply", emote);
                enqueueUi(() -> threadTab.appendOptimistic(ownLine));
            });
        });
        input.setSubmitAction(() -> {
            String text = input.text().strip();
            if (text.isEmpty()) return;
            replySender.send(threadTab.replyTargetId(), text, () -> {
                ChatLine ownLine = new ChatLine(null, ownUsername, text, true);
                enqueueChatLine(ownLine);
                showSentMessage("Thread reply", text);
                enqueueUi(() -> {
                    threadTab.appendOptimistic(ownLine);
                    clearSentText(input, text);
                });
            });
        });
        send.clicked.connect(input::submit);
        return threadTab;
    }

    private void closeTabAt(int index) {
        if (index <= 1) return;
        QWidget panel = tabs.widget(index);
        for (Map.Entry<String, ThreadTab> entry : new ArrayList<>(threadTabs.entrySet())) {
            if (entry.getValue().panel == panel) {
                closeThread(entry.getKey());
                return;
            }
        }
    }

    private void closeThread(String rootId) {
        ThreadTab removed = threadTabs.remove(rootId);
        if (removed == null) return;
        if (activeComposerInput == removed.input) activeComposerInput = publicChatInput;
        int index = tabs.indexOf(removed.panel);
        if (index >= 0) tabs.removeTab(index);
        removed.panel.disposeLater();
        forgetThreadMessages(rootId);
    }

    private void forgetThreadMessages(String rootId) {
        synchronized (messages) {
            messages.entrySet().removeIf(entry -> {
                ChatLine line = entry.getValue();
                String lineRootId = firstNonBlank(line.rootId(), line.id());
                return rootId.equals(lineRootId);
            });
        }
    }

    private void enforceThreadTabLimit() {
        if (threadTabs.size() < MAX_THREAD_TABS) return;
        QWidget selected = tabs.currentWidget();
        String oldestId = null;
        long oldestActivity = Long.MAX_VALUE;
        for (Map.Entry<String, ThreadTab> entry : threadTabs.entrySet()) {
            ThreadTab candidate = entry.getValue();
            if (candidate.panel != selected && candidate.lastActivityAtMillis < oldestActivity) {
                oldestActivity = candidate.lastActivityAtMillis;
                oldestId = entry.getKey();
            }
        }
        if (oldestId != null) closeThread(oldestId);
    }

    private void markSelectedThreadRead() {
        QWidget selected = tabs.currentWidget();
        for (ThreadTab tab : threadTabs.values()) {
            if (tab.panel == selected) {
                activeComposerInput = tab.input;
                tab.markRead();
                return;
            }
        }
        activeComposerInput = publicChatInput;
    }

    private void updateTabAges() {
        long now = System.currentTimeMillis();
        long redAfter = Duration.ofMinutes(30).toMillis();
        long closeAfter = Duration.ofMinutes(35).toMillis();
        List<String> expired = new ArrayList<>();
        for (Map.Entry<String, ThreadTab> entry : threadTabs.entrySet()) {
            ThreadTab tab = entry.getValue();
            if (!tab.involvesOwnUser && now - tab.lastActivityAtMillis >= closeAfter) expired.add(entry.getKey());
            else tab.refreshAppearance(now, redAfter);
        }
        expired.forEach(this::closeThread);
    }

    private void rememberMessage(ChatLine line) {
        long oldestAllowed = System.currentTimeMillis() - CHAT_RETENTION_MS;
        synchronized (messages) {
            messages.put(line.id(), line);
            var iterator = messages.entrySet().iterator();
            while (iterator.hasNext()) {
                ChatLine cached = iterator.next().getValue();
                if (messages.size() <= MAX_CACHED_MESSAGES && cached.receivedAtMillis() >= oldestAllowed) break;
                iterator.remove();
            }
        }
    }

    private void applyStyleSheet() {
        window.setStyleSheet("""
            QMainWindow { background:#11151b; }
            QWidget { background:transparent; color:#dce3ec; font-family:'Segoe UI'; font-size:13px; }
            QWidget#central { background:#11151b; }
            QWidget#panel { background:#171c24; border:none; border-radius:4px; }
            QWidget#bottomBar { background:#11151b; }
            QWidget#bottomBar QPushButton { padding:4px 10px; }
            QLabel#title { font-size:16px; font-weight:700; color:#ffffff; }
            QLabel#channelBadge { background:#6d38d6; color:white; padding:2px 7px; border-radius:8px; font-weight:700; }
            QLabel#status { color:#9ba7b5; padding:0 6px; }
            QLabel#sectionTitle { color:#aab6c5; font-size:11px; font-weight:700; letter-spacing:1px; }
            QLabel#hint { color:#9ba7b5; }
            QListWidget { background:#0b0e12; color:#d7dde6; border:none; border-radius:3px; outline:0; padding:2px; }
            QListWidget::item { min-height:18px; padding:0 3px; border-radius:2px; }
            QListWidget::item:selected { background:#344057; color:white; }
            QListWidget#watchedChat::item { min-height:18px; }
            QListWidget#emotePicker::item { padding:2px; }
            QStackedWidget#threadStack { border:none; border-radius:3px; background:#171c24; }
            QPushButton#wrapTab { background:#202733; color:#aeb8c5; padding:6px 8px; border:none; border-radius:4px; text-align:left; }
            QPushButton#wrapTab:checked { background:#38465c; }
            QPushButton#wrapTabClose { background:#202733; color:#98a4b3; padding:3px 2px; border:none; border-radius:4px; }
            QPushButton#wrapTabClose:hover { background:#713747; color:white; }
            QTextEdit, QPlainTextEdit, QLineEdit { background:#0c1015; color:#e9eef5; border:1px solid #283240; border-radius:4px; padding:6px; selection-background-color:#6546c7; }
            QTextEdit#transcript { font-family:'Cascadia Mono','Consolas'; }
            QPlainTextEdit#sentMessages { font-family:'Cascadia Mono','Consolas'; }
            QPlainTextEdit#chattersView { font-family:'Cascadia Mono','Consolas'; padding:4px; }
            QPushButton { background:#6441a5; color:white; border:none; border-radius:4px; padding:7px 13px; font-weight:600; }
            QPushButton:hover { background:#7551b8; }
            QPushButton:pressed { background:#4f328a; }
            QPushButton#greetingOff { background:#3a2430; color:#ffb5c5; border-color:#724052; }
            QPushButton#secondaryButton { background:#263140; border-color:#405066; }
            QSplitter::handle { background:#202733; }
            QStatusBar { background:#11151b; color:#9ba7b5; }
            QScrollBar:vertical { background:#11151b; width:10px; margin:0; }
            QScrollBar::handle:vertical { background:#3c4859; min-height:30px; border-radius:5px; }
            QScrollBar::add-line:vertical, QScrollBar::sub-line:vertical { height:0; }
            """);
    }

    private void rememberUsername(String login, String displayName) {
        if (login == null || login.isBlank()) return;
        String normalized = login.strip().toLowerCase(Locale.ROOT);
        String displayed = displayName == null || displayName.isBlank() ? login.strip() : displayName.strip();
        if (displayed.startsWith("@")) displayed = displayed.substring(1);
        synchronized (knownUsernames) {
            knownUsernames.putIfAbsent(normalized, displayed);
            while (knownUsernames.size() > MAX_KNOWN_USERNAMES) {
                String oldest = knownUsernames.keySet().iterator().next();
                knownUsernames.remove(oldest);
            }
        }
    }

    private List<String> completeChatToken(String prefix) {
        if (prefix == null || prefix.isBlank()) return List.of();
        LinkedHashSet<String> completions = new LinkedHashSet<>();
        if (!prefix.startsWith("@")) completions.addAll(emotes.complete(prefix));

        String nicknamePrefix = prefix.startsWith("@") ? prefix.substring(1) : prefix;
        if (!nicknamePrefix.isBlank()) {
            String normalizedPrefix = nicknamePrefix.toLowerCase(Locale.ROOT);
            List<String> usernames;
            synchronized (knownUsernames) {
                usernames = new ArrayList<>(knownUsernames.values());
            }
            usernames.stream()
                .filter(username -> username.toLowerCase(Locale.ROOT).startsWith(normalizedPrefix))
                .sorted(Comparator.comparingInt(String::length)
                    .thenComparing(String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(Comparator.naturalOrder()))
                .limit(100)
                .map(username -> "@" + username)
                .forEach(completions::add);
        }
        return List.copyOf(completions);
    }

    private boolean isKnownUsername(String value) {
        if (value == null || value.isBlank()) return false;
        String normalized = value.startsWith("@") ? value.substring(1) : value;
        synchronized (knownUsernames) {
            return knownUsernames.containsKey(normalized.toLowerCase(Locale.ROOT));
        }
    }

    private static QColor nicknameBackground(String nickname) {
        int hash = nickname.toLowerCase(Locale.ROOT).hashCode();
        return QColor.fromHsv(Math.floorMod(hash, 360), 135 + Math.floorMod(hash >>> 8, 55),
            145 + Math.floorMod(hash >>> 16, 65));
    }

    private static QColor nicknameColor(ChatLine line) {
        String supplied = line.nicknameColor();
        if (supplied != null && supplied.matches("(?i)^#[0-9a-f]{6}$")) {
            QColor twitchColor = new QColor(supplied);
            // Twitch permits very dark chat colors. Lift them enough for this dark UI.
            if (relativeLuminance(twitchColor) < 0.12) return twitchColor.lighter(180);
            return twitchColor;
        }
        return nicknameBackground(line.author()).lighter(145);
    }

    /** Nickname color at 30% opacity over black: 70% of the background remains black. */
    private static QColor messageBackgroundColor(ChatLine line) {
        QColor nickname = nicknameColor(line);
        double opacity = 0.20;
        return new QColor(
            (int) Math.round(nickname.red() * opacity),
            (int) Math.round(nickname.green() * opacity),
            (int) Math.round(nickname.blue() * opacity)
        );
    }

    private QTextDocument createMessageDocument(ChatLine line, QFont font, int width, QColor authorColor) {
        QTextDocument document = new QTextDocument();
        document.setDefaultFont(font);
        document.setDocumentMargin(0);
        document.setTextWidth(Math.max(1, width));
        QTextCursor cursor = new QTextCursor(document);
        QTextCharFormat normal = new QTextCharFormat();
        normal.setForeground(new QColor("#e4e9f0"));
        QTextCharFormat authorFormat = new QTextCharFormat(normal);
        authorFormat.setForeground(authorColor);
        String author = line.author() == null ? "?" : line.author();
        cursor.insertText(author + ":", authorFormat);
        cursor.insertText(" ", normal);
        insertMessageWithEmotes(cursor, line.message() == null ? "" : line.message(), normal);
        return document;
    }

    private void insertMessageWithEmotes(QTextCursor cursor, String message, QTextCharFormat normal) {
        QTextCharFormat bold = new QTextCharFormat(normal);
        bold.setFontWeight(QFont.Weight.Bold.value());
        int position = 0;
        for (EmoteCatalog.EmoteRange range : emotes.findRanges(message)) {
            if (range.start() > position) {
                cursor.insertText(message.substring(position, range.start()), normal);
            }
            String code = message.substring(range.start(), range.end());
            if (!chatEmotesEnabled || !insertChatEmoteImage(cursor, code)) {
                cursor.insertText(code, bold);
            }
            position = range.end();
        }
        if (position < message.length()) {
            cursor.insertText(message.substring(position), normal);
        }
    }

    private boolean insertChatEmoteImage(QTextCursor cursor, String code) {
        Path path = chatEmoteImagePaths.get(code);
        if (path == null) {
            requestChatEmoteImage(code);
            return false;
        }
        QPixmap pixmap = currentChatEmotePixmap(code, path);
        if (pixmap == null || pixmap.isNull()) {
            if (pixmap != null) pixmap.dispose();
            return false;
        }
        String resourceName = chatEmoteResourceName(code);
        cursor.document().addResource(QTextDocument.ResourceType.ImageResource.value(), resourceName, pixmap);
        QTextImageFormat imageFormat = new QTextImageFormat();
        imageFormat.setName(resourceName);
        int sourceHeight = Math.max(1, pixmap.height());
        int displayWidth = Math.max(1, (int) Math.round(
            pixmap.width() * (double) emoteIconSize / sourceHeight
        ));
        imageFormat.setWidth(displayWidth);
        imageFormat.setHeight(emoteIconSize);
        imageFormat.setVerticalAlignment(QTextCharFormat.VerticalAlignment.AlignMiddle);
        cursor.insertImage(imageFormat);
        imageFormat.dispose();
        pixmap.dispose();
        return true;
    }

    private QPixmap currentChatEmotePixmap(String code, Path path) {
        QMovie movie = chatEmoteMovies.get(code);
        if (movie != null && movie.isValid()) {
            QPixmap frame = movie.currentPixmap();
            if (!frame.isNull()) return frame;
            frame.dispose();
        }
        return new QPixmap(path.toString());
    }

    private void requestChatEmoteImage(String code) {
        if (!chatEmotesEnabled || chatEmoteImagePaths.containsKey(code)
            || !chatEmoteImageLoadsRequested.add(code)) return;
        EmoteCatalog.EmoteInfo info = emotes.info(code);
        if (info == null) {
            chatEmoteImageLoadsRequested.remove(code);
            return;
        }
        emotes.loadImageAsync(
            info,
            path -> enqueueUi(() -> {
                chatEmoteImageLoadsRequested.remove(code);
                chatEmoteImagePaths.put(code, path);
                if (emoteAnimationEnabled && info.animated()) startChatEmoteMovie(code, path);
                scheduleChatEmoteRefresh();
            }),
            () -> enqueueUi(() -> chatEmoteImageLoadsRequested.remove(code))
        );
    }

    private void scheduleChatEmoteRefresh() {
        if (chatEmoteRefreshScheduled) return;
        chatEmoteRefreshScheduled = true;
        QTimer.singleShot(50, () -> {
            chatEmoteRefreshScheduled = false;
            if (closed) return;
            clearMessageDocumentCaches();
            for (ThreadTab tab : threadTabs.values()) tab.refreshTranscript();
            chatList.doItemsLayout();
            watchedList.doItemsLayout();
            chatList.viewport().update();
            watchedList.viewport().update();
        });
    }

    private void refreshChatEmoteAnimations() {
        if (!chatEmotesEnabled) return;
        if (!emoteAnimationEnabled) {
            stopAllChatEmoteMovies();
            scheduleChatEmoteRefresh();
            return;
        }
        for (Map.Entry<String, Path> entry : chatEmoteImagePaths.entrySet()) {
            EmoteCatalog.EmoteInfo info = emotes.info(entry.getKey());
            if (info != null && info.animated()) startChatEmoteMovie(entry.getKey(), entry.getValue());
        }
        scheduleChatEmoteRefresh();
    }

    private void startChatEmoteMovie(String code, Path path) {
        if (chatEmoteMovies.containsKey(code)) return;
        QMovie movie = new QMovie(path.toString());
        if (!movie.isValid()) {
            movie.dispose();
            return;
        }
        movie.frameChanged.connect(frame -> {
            if (chatEmoteMovies.get(code) != movie) return;
            QPixmap pixmap = movie.currentPixmap();
            if (!pixmap.isNull()) updateChatEmoteFrame(code, pixmap);
            pixmap.dispose();
        });
        chatEmoteMovies.put(code, movie);
        movie.start();
    }

    private void updateChatEmoteFrame(String code, QPixmap pixmap) {
        if (chatLineDelegate != null) chatLineDelegate.updateEmoteFrame(code, pixmap);
        if (watchedLineDelegate != null) watchedLineDelegate.updateEmoteFrame(code, pixmap);
        for (ThreadTab tab : threadTabs.values()) tab.updateEmoteFrame(code, pixmap);
    }

    private void stopAllChatEmoteMovies() {
        for (QMovie movie : chatEmoteMovies.values()) {
            movie.stop();
            movie.dispose();
        }
        chatEmoteMovies.clear();
    }

    private static String chatEmoteResourceName(String code) {
        return "chat-emote:" + code;
    }

    private Set<String> emoteCodesIn(String message) {
        Set<String> result = new HashSet<>();
        for (EmoteCatalog.EmoteRange range : emotes.findRanges(message == null ? "" : message)) {
            result.add(message.substring(range.start(), range.end()));
        }
        return result;
    }

    private void updateDocumentEmoteFrame(QTextDocument document, String code, QPixmap pixmap) {
        document.addResource(
            QTextDocument.ResourceType.ImageResource.value(),
            chatEmoteResourceName(code),
            pixmap
        );
        document.markContentsDirty(0, document.characterCount());
    }

    private static QColor contrastingText(QColor background) {
        double luminance = relativeLuminance(background);
        return new QColor(luminance > 0.179 ? "#080a0d" : "#ffffff");
    }

    private static double relativeLuminance(QColor color) {
        return 0.2126 * linearColorComponent(color.red())
            + 0.7152 * linearColorComponent(color.green())
            + 0.0722 * linearColorComponent(color.blue());
    }

    private static double linearColorComponent(int component) {
        double value = component / 255.0;
        return value <= 0.04045 ? value / 12.92 : Math.pow((value + 0.055) / 1.055, 2.4);
    }

    private static boolean isExcludedThreadUser(String username, Set<String> excludedUsers) {
        return username != null && !username.isBlank()
            && (username.equalsIgnoreCase("StreamElements")
                || excludedUsers.contains(username.toLowerCase(Locale.ROOT)));
    }

    private boolean mentionsOwnUsername(String message) {
        Pattern pattern = ownMentionPattern.get();
        return message != null && pattern != null && pattern.matcher(message).find();
    }

    private static String shortTitle(String title) {
        String value = title == null || title.isBlank() ? "thread" : title;
        return value.length() <= 24 ? value : value.substring(0, 21) + "...";
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value;
        return null;
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        chatScrollTimer.stop();
        uiTimer.stop();
        ageTimer.stop();
        closeEmotePopup();
        stopAllEmoteItemMovies();
        stopAllChatEmoteMovies();
        clearEmoteItemIcons();
        clearMessageDocumentCaches();
        spellChecker.close();
        emotes.close();
        window.close();
    }

    private record ChatLine(String id, String author, String message, long receivedAtMillis,
                            String rootId, String rootAuthor, boolean watched, String nicknameColor,
                            boolean ownMessage, boolean addressedToOwnUser) {
        private ChatLine(String id, String author, String message) {
            this(id, author, message, false);
        }
        private ChatLine(String id, String author, String message, boolean ownMessage) {
            this(id, author, message, System.currentTimeMillis(), id, author, false, null, ownMessage, false);
        }
        private ChatLine withWatched(boolean value) {
            return new ChatLine(
                id,
                author,
                message,
                receivedAtMillis,
                rootId,
                rootAuthor,
                value,
                nicknameColor,
                ownMessage,
                addressedToOwnUser
            );
        }
    }

    private record PendingChatLine(ChatLine line, boolean watched, boolean hidden) {}
    private record TrimResult(boolean removed, int visiblePixels) {}
    record ChatViewSettings(
        boolean removeEmoteOnly,
        boolean removeChinese,
        boolean removeJapanese,
        boolean removeKorean,
        boolean removeLatin,
        Set<Character.UnicodeScript> filteredScripts,
        String threadAutoOpenMode,
        List<String> hiddenTexts,
        int minimumMessageLength,
        int fontPointSize,
        int lineSpacing
    ) {
        ChatViewSettings {
            threadAutoOpenMode = threadAutoOpenMode.toLowerCase(Locale.ROOT);
            hiddenTexts = List.copyOf(hiddenTexts);
            filteredScripts = Set.copyOf(filteredScripts);
        }
    }

    /** A lightweight replacement for QTabWidget whose tab buttons wrap into multiple rows. */
    private static final class WrappingTabWidget extends QWidget {
        private final Header header = new Header();
        private final QGridLayout tabLayout = new QGridLayout(header);
        private final QStackedWidget stack = new QStackedWidget();
        private final List<TabEntry> entries = new ArrayList<>();
        private IntConsumer currentChangedHandler = ignored -> {};
        private IntConsumer closeRequestedHandler = ignored -> {};
        private boolean relayoutScheduled;

        private WrappingTabWidget() {
            setMinimumSize(0, 0);
            setSizePolicy(QSizePolicy.Policy.Ignored, QSizePolicy.Policy.Expanding);
            QVBoxLayout layout = new QVBoxLayout(this);
            layout.setContentsMargins(0, 0, 0, 0);
            layout.setSpacing(3);
            tabLayout.setContentsMargins(0, 0, 0, 0);
            tabLayout.setHorizontalSpacing(8);
            tabLayout.setVerticalSpacing(3);
            stack.setObjectName("threadStack");
            layout.addWidget(header);
            layout.addWidget(stack, 1);
        }

        @Override
        public QSize minimumSizeHint() {
            return new QSize(0, 0);
        }

        private void setCurrentChangedHandler(IntConsumer handler) {
            currentChangedHandler = handler == null ? ignored -> {} : handler;
        }

        private void setCloseRequestedHandler(IntConsumer handler) {
            closeRequestedHandler = handler == null ? ignored -> {} : handler;
        }

        private int addPermanentTab(QWidget page, String title) {
            return insertTab(entries.size(), page, title, false);
        }

        private int insertTab(int requestedIndex, QWidget page, String title) {
            return insertTab(requestedIndex, page, title, true);
        }

        private int insertTab(int requestedIndex, QWidget page, String title, boolean closable) {
            int index = Math.max(0, Math.min(requestedIndex, entries.size()));
            QWidget container = new QWidget();
            QHBoxLayout row = new QHBoxLayout(container);
            row.setContentsMargins(0, 0, 0, 0);
            row.setSpacing(2);
            QPushButton button = new QPushButton(title);
            button.setObjectName("wrapTab");
            button.setCheckable(true);
            row.addWidget(button, 1);
            QPushButton close = null;
            if (closable) {
                close = new QPushButton("×");
                close.setObjectName("wrapTabClose");
                close.setFixedWidth(19);
                row.addWidget(close);
            }
            TabEntry entry = new TabEntry(page, container, button, close);
            entries.add(index, entry);
            stack.insertWidget(index, page);
            button.clicked.connect(() -> setCurrentWidget(entry.page));
            if (close != null) {
                close.clicked.connect(() -> {
                    int currentIndex = entries.indexOf(entry);
                    if (currentIndex >= 0) closeRequestedHandler.accept(currentIndex);
                });
            }
            if (entries.size() == 1) setCurrentWidget(page);
            scheduleRelayout();
            refreshButtons();
            return index;
        }

        private void setCurrentWidget(QWidget page) {
            int index = indexOf(page);
            if (index < 0 || stack.currentWidget() == page) return;
            stack.setCurrentWidget(page);
            refreshButtons();
            currentChangedHandler.accept(index);
        }

        private QWidget currentWidget() {
            return stack.currentWidget();
        }

        private QWidget widget(int index) {
            return index >= 0 && index < entries.size() ? entries.get(index).page : null;
        }

        private int indexOf(QWidget page) {
            for (int index = 0; index < entries.size(); index++) {
                if (entries.get(index).page == page) return index;
            }
            return -1;
        }

        private void removeTab(int index) {
            if (index < 0 || index >= entries.size()) return;
            TabEntry removed = entries.get(index);
            boolean wasCurrent = stack.currentWidget() == removed.page;
            tabLayout.removeWidget(removed.container);
            stack.removeWidget(removed.page);
            entries.remove(index);
            removed.container.dispose();
            if (wasCurrent && !entries.isEmpty()) {
                int next = Math.min(index, entries.size() - 1);
                stack.setCurrentWidget(entries.get(next).page);
                currentChangedHandler.accept(next);
            }
            scheduleRelayout();
            refreshButtons();
        }

        private void setTabText(int index, String text) {
            if (index < 0 || index >= entries.size()) return;
            entries.get(index).button.setText(text);
            scheduleRelayout();
        }

        private void setTabTextColor(int index, QColor color) {
            if (index < 0 || index >= entries.size()) return;
            entries.get(index).textColor = color;
            refreshButtons();
        }

        private void setTabToolTip(int index, String tooltip) {
            if (index < 0 || index >= entries.size()) return;
            TabEntry entry = entries.get(index);
            entry.button.setToolTip(tooltip);
            entry.container.setToolTip(tooltip);
        }

        private void refreshButtons() {
            QWidget selected = stack.currentWidget();
            for (TabEntry entry : entries) {
                entry.button.setChecked(entry.page == selected);
                entry.button.setStyleSheet("color:" + entry.textColor.name() + ";");
            }
        }

        private void scheduleRelayout() {
            if (relayoutScheduled) return;
            relayoutScheduled = true;
            QTimer.singleShot(0, () -> {
                relayoutScheduled = false;
                relayoutTabs();
            });
        }

        private void relayoutTabs() {
            int available = Math.max(200, header.width());
            int row = 0;
            int column = 0;
            int used = 0;
            int rowHeight = 30;
            for (TabEntry entry : entries) {
                tabLayout.removeWidget(entry.container);
                int naturalWidth = entry.button.sizeHint().width() + (entry.close == null ? 14 : 42);
                int desired = Math.max(84, Math.min(208,
                    (int) Math.round(naturalWidth * THREAD_TAB_WIDTH_SCALE)));
                entry.container.setFixedWidth(desired);
                rowHeight = Math.max(rowHeight, entry.container.sizeHint().height());
                if (used > 0 && used + desired + 8 > available) {
                    row++;
                    column = 0;
                    used = 0;
                }
                tabLayout.addWidget(entry.container, row, column++);
                used += desired + 8;
            }
            header.setFixedHeight(Math.max(rowHeight, (row + 1) * (rowHeight + 3) - 3));
        }

        private final class Header extends QWidget {
            private Header() {
                setSizePolicy(QSizePolicy.Policy.Ignored, QSizePolicy.Policy.Fixed);
            }

            @Override
            public QSize minimumSizeHint() {
                return new QSize(0, Math.max(30, height()));
            }

            @Override
            protected void resizeEvent(QResizeEvent event) {
                super.resizeEvent(event);
                scheduleRelayout();
            }
        }

        private static final class TabEntry {
            private final QWidget page;
            private final QWidget container;
            private final QPushButton button;
            private final QPushButton close;
            private QColor textColor = new QColor("#b7c0cc");

            private TabEntry(QWidget page, QWidget container, QPushButton button, QPushButton close) {
                this.page = page;
                this.container = container;
                this.button = button;
                this.close = close;
            }
        }
    }

    /** Paints only the nickname with the Twitch IRC color, without allocating a widget per message. */
    private final class ChatLineDelegate extends QStyledItemDelegate {
        private final QListWidget list;
        private final boolean showChannel;
        private final int maximumCacheEntries;
        private final LinkedHashMap<QListWidgetItem, CachedMessageDocument> documentCache =
            new LinkedHashMap<>(64, 0.75f, true);

        private ChatLineDelegate(QListWidget list, boolean showChannel) {
            super(list);
            this.list = list;
            this.showChannel = showChannel;
            this.maximumCacheEntries = showChannel
                ? MAX_VISIBLE_CHAT_LINES + MAX_CHAT_LINES_PER_TICK
                : MAX_WATCHED_CHAT_LINES + MAX_CHAT_LINES_PER_TICK;
        }

        @Override
        public void paint(QPainter painter, QStyleOptionViewItem option, QModelIndex index) {
            QListWidgetItem item = list.item(index.row());
            ChatLine line = showChannel ? chatItemLines.get(item) : watchedItemLines.get(item);
            if (line == null) {
                super.paint(painter, option, index);
                return;
            }

            painter.save();
            painter.setClipRect(option.rect());
            painter.setFont(option.font());
            QFontMetrics metrics = painter.fontMetrics();
            int x = option.rect().x() + 5;
            String nickname = line.author() == null ? "?" : line.author();
            String nicknameLabel = nickname + ":";
            int nicknameWidth = metrics.horizontalAdvance(nicknameLabel);
            int contentWidth = messageWidth(option.rect().width());
            CachedMessageDocument cached = documentFor(item, line, option.font(), contentWidth);
            FirstLineGeometry firstLine = firstLineGeometry(cached.document(), metrics);
            painter.fillRect(
                option.rect(),
                item.isSelected() ? new QColor("#344057") : cached.rowBackgroundColor()
            );
            painter.fillRect(
                x,
                option.rect().y(),
                Math.max(0, option.rect().right() - x + 1),
                option.rect().height(),
                cached.messageBackgroundColor()
            );
            painter.fillRect(
                option.rect().x(),
                option.rect().y(),
                nicknameWidth + 5,
                firstLine.height() + 2,
                item.isSelected() ? new QColor("#344057") : cached.rowBackgroundColor()
            );
            if (line.addressedToOwnUser()) {
                QColor borderColor = new QColor("#ffffff");
                QPen borderPen = new QPen(borderColor, 1.0);
                var borderRect = option.rect().adjusted(0, 0, -1, -1);
                painter.setPen(borderPen);
                painter.drawRect(borderRect);
                borderRect.dispose();
                borderPen.dispose();
                borderColor.dispose();
            }
            painter.translate(x, option.rect().y() + 1);
            cached.document().drawContents(painter);
            painter.restore();
        }

        @Override
        public QSize sizeHint(QStyleOptionViewItem option, QModelIndex index) {
            QListWidgetItem item = list.item(index.row());
            ChatLine line = showChannel ? chatItemLines.get(item) : watchedItemLines.get(item);
            if (line == null) return super.sizeHint(option, index);

            QFontMetrics metrics = new QFontMetrics(option.font());
            int contentWidth = messageWidth(list.viewport().width());
            QTextDocument document = documentFor(item, line, option.font(), contentWidth).document();
            int documentHeight = (int) Math.ceil(document.size().height());
            return new QSize(list.viewport().width(), Math.max(metrics.lineSpacing(), documentHeight) + 2);
        }

        private int messageWidth(int availableWidth) {
            int exactWidth = Math.max(1, availableWidth - 6);
            // Small differences between sizeHint() and paint() must not invalidate every cached row.
            return exactWidth < 8 ? exactWidth : exactWidth - Math.floorMod(exactWidth, 8);
        }

        private FirstLineGeometry firstLineGeometry(QTextDocument document, QFontMetrics fallback) {
            document.size(); // Force QTextDocument to calculate image-aware line geometry.
            var block = document.firstBlock();
            var layout = block.layout();
            if (layout != null && layout.lineCount() > 0) {
                var line = layout.lineAt(0);
                return new FirstLineGeometry(
                    Math.max(fallback.lineSpacing(), (int) Math.ceil(line.height()))
                );
            }
            return new FirstLineGeometry(fallback.lineSpacing());
        }

        private CachedMessageDocument documentFor(
            QListWidgetItem item,
            ChatLine line,
            QFont font,
            int width
        ) {
            CachedMessageDocument cached = documentCache.get(item);
            if (cached != null && cached.width() == width) return cached;
            if (cached != null) cached.document().dispose();
            QColor authorColor = nicknameColor(line);
            CachedMessageDocument replacement = new CachedMessageDocument(
                width,
                createMessageDocument(line, font, width, authorColor),
                emoteCodesIn(line.message()),
                messageBackgroundColor(line),
                line.watched()
                    ? new QColor("#183029")
                    : line.ownMessage() || ownUsername.equalsIgnoreCase(line.author())
                        ? new QColor("#32290f")
                        : new QColor("#0b0e12")
            );
            documentCache.put(item, replacement);
            trimCache();
            return replacement;
        }

        private void trimCache() {
            while (documentCache.size() > maximumCacheEntries) {
                Map.Entry<QListWidgetItem, CachedMessageDocument> oldest =
                    documentCache.entrySet().iterator().next();
                documentCache.remove(oldest.getKey());
                oldest.getValue().document().dispose();
            }
        }

        private void removeCached(QListWidgetItem item) {
            CachedMessageDocument removed = documentCache.remove(item);
            if (removed != null) removed.document().dispose();
        }

        private void clearCache() {
            for (CachedMessageDocument cached : documentCache.values()) {
                cached.document().dispose();
            }
            documentCache.clear();
        }

        private void updateEmoteFrame(String code, QPixmap pixmap) {
            boolean changed = false;
            for (CachedMessageDocument cached : documentCache.values()) {
                if (!cached.emoteCodes().contains(code)) continue;
                updateDocumentEmoteFrame(cached.document(), code, pixmap);
                changed = true;
            }
            if (changed) list.viewport().update();
        }
    }

    private record CachedMessageDocument(
        int width,
        QTextDocument document,
        Set<String> emoteCodes,
        QColor messageBackgroundColor,
        QColor rowBackgroundColor
    ) {}

    private record FirstLineGeometry(int height) {}

    private final class EmotePickerList extends QListWidget {
        @Override
        protected void keyPressEvent(QKeyEvent event) {
            int key = event.key();
            if (key == Qt.Key.Key_Return.value()
                || key == Qt.Key.Key_Enter.value()
                || key == Qt.Key.Key_Space.value()) {
                QListWidgetItem selected = currentItem();
                if (selected != null && !selected.isHidden()) {
                    selectEmoteFromPicker(selected);
                } else {
                    QApplication.beep();
                }
                event.accept();
                return;
            }
            super.keyPressEvent(event);
        }

        @Override
        protected void resizeEvent(QResizeEvent event) {
            super.resizeEvent(event);
            QTimer.singleShot(0, ThreadViewer.this::requestVisibleEmoteImages);
        }

        @Override
        protected void leaveEvent(QEvent event) {
            closeEmotePopup();
            super.leaveEvent(event);
        }
    }

    private final class EmoteMessageList extends QListWidget {
        private final boolean showChannel;
        private String hoveredEmoteCode;

        private EmoteMessageList(boolean showChannel) {
            this.showChannel = showChannel;
        }

        @Override
        protected void wheelEvent(QWheelEvent event) {
            if (showChannel) beginManualChatScroll();
            super.wheelEvent(event);
            if (showChannel) finishManualChatScroll();
        }

        @Override
        protected void mouseMoveEvent(QMouseEvent event) {
            QListWidgetItem item = itemAt(event.pos());
            ChatLine line = item == null
                ? null
                : showChannel ? chatItemLines.get(item) : watchedItemLines.get(item);
            String emote = line == null ? null : emoteAtListPosition(item, line, event.pos());
            if (!java.util.Objects.equals(hoveredEmoteCode, emote)) {
                hoveredEmoteCode = emote;
                if (emote == null) {
                    closeEmotePopup();
                } else {
                    showEmotePreview(emote);
                }
            }
            super.mouseMoveEvent(event);
        }

        @Override
        protected void leaveEvent(QEvent event) {
            hoveredEmoteCode = null;
            closeEmotePopup();
            super.leaveEvent(event);
        }

        @Override
        protected void keyPressEvent(QKeyEvent event) {
            int key = event.key();
            boolean scrollKey = key == Qt.Key.Key_Up.value()
                || key == Qt.Key.Key_Down.value()
                || key == Qt.Key.Key_PageUp.value()
                || key == Qt.Key.Key_PageDown.value()
                || key == Qt.Key.Key_Home.value()
                || key == Qt.Key.Key_End.value()
                || key == Qt.Key.Key_Space.value();
            if (showChannel && scrollKey) beginManualChatScroll();
            super.keyPressEvent(event);
            if (showChannel && scrollKey) finishManualChatScroll();
        }

        @Override
        protected void contextMenuEvent(QContextMenuEvent event) {
            QListWidgetItem item = itemAt(event.pos());
            ChatLine line = item == null
                ? null
                : showChannel ? chatItemLines.get(item) : watchedItemLines.get(item);
            String emote = line == null ? null : emoteAtListPosition(item, line, event.pos());
            if (emote != null) {
                sendPublicEmote(emote);
                event.accept();
                return;
            }
            super.contextMenuEvent(event);
        }

        private String emoteAtListPosition(QListWidgetItem item, ChatLine line, QPoint position) {
            var itemRect = visualItemRect(item);
            try {
                String nickname = line.author() == null ? "?" : line.author();
                int documentX = position.x() - itemRect.x() - 5;
                int documentY = position.y() - itemRect.y() - 1;
                if (documentX < 0 || documentY < 0) return null;

                int messageWidth = Math.max(1, itemRect.width() - 6);
                QTextDocument document = createMessageDocument(
                    line,
                    font(),
                    messageWidth,
                    nicknameColor(line)
                );
                QPoint documentPosition = new QPoint(documentX, documentY);
                try {
                    int hit = document.documentLayout().hitTest(
                        documentPosition,
                        Qt.HitTestAccuracy.ExactHit
                    );
                    String imageEmote = emoteImageAt(document, hit);
                    if (imageEmote != null) return imageEmote;
                    int messageOffset = nickname.length() + 2;
                    return exactEmoteAt(line.message(), hit - messageOffset);
                } finally {
                    documentPosition.dispose();
                    document.dispose();
                }
            } finally {
                itemRect.dispose();
            }
        }
    }

    private final class EmoteTranscript extends QTextEdit {
        private Consumer<String> emoteAction = ignored -> {};
        private Consumer<String> replyAction = ignored -> {};
        private final List<String> replyMessageIds = new ArrayList<>();
        private String hoveredEmoteCode;

        private EmoteTranscript() {
            setMouseTracking(true);
            viewport().setMouseTracking(true);
        }

        private void setEmoteAction(Consumer<String> action) {
            emoteAction = action == null ? ignored -> {} : action;
        }

        private void setReplyAction(Consumer<String> action) {
            replyAction = action == null ? ignored -> {} : action;
        }

        private void registerMessage(String messageId) {
            replyMessageIds.add(messageId);
            int visibleMessageBlocks = Math.max(0, document().blockCount() - 1);
            while (replyMessageIds.size() > visibleMessageBlocks) replyMessageIds.remove(0);
        }

        private void bindLatestUnassignedMessage(String messageId) {
            for (int index = replyMessageIds.size() - 1; index >= 0; index--) {
                if (replyMessageIds.get(index) == null) {
                    replyMessageIds.set(index, messageId);
                    return;
                }
            }
        }

        private void clearRegisteredMessages() {
            replyMessageIds.clear();
        }

        @Override
        protected void mousePressEvent(QMouseEvent event) {
            String replyMessageId = null;
            if (event.button() == Qt.MouseButton.LeftButton) {
                QTextCursor cursor = cursorForPosition(event.pos());
                int blockNumber = cursor.blockNumber();
                cursor.dispose();
                if (blockNumber >= 0 && blockNumber < replyMessageIds.size()) {
                    replyMessageId = replyMessageIds.get(blockNumber);
                }
            }
            super.mousePressEvent(event);
            if (replyMessageId != null) {
                QTextCursor selection = cursorForPosition(event.pos());
                selection.select(QTextCursor.SelectionType.BlockUnderCursor);
                setTextCursor(selection);
                selection.dispose();
                replyAction.accept(replyMessageId);
            }
        }

        @Override
        protected void mouseMoveEvent(QMouseEvent event) {
            QTextCursor cursor = cursorForPosition(event.pos());
            String emote = emoteImageAt(document(), cursor.position());
            if (emote == null) emote = exactEmoteAt(toPlainText(), cursor.position());
            cursor.dispose();
            if (!java.util.Objects.equals(hoveredEmoteCode, emote)) {
                hoveredEmoteCode = emote;
                if (emote == null) {
                    closeEmotePopup();
                } else {
                    showEmotePreview(emote);
                }
            }
            super.mouseMoveEvent(event);
        }

        @Override
        protected void leaveEvent(QEvent event) {
            hoveredEmoteCode = null;
            closeEmotePopup();
            super.leaveEvent(event);
        }

        @Override
        protected void contextMenuEvent(QContextMenuEvent event) {
            QTextCursor cursor = cursorForPosition(event.pos());
            String emote = emoteImageAt(document(), cursor.position());
            if (emote == null) emote = exactEmoteAt(toPlainText(), cursor.position());
            cursor.dispose();
            if (emote != null) {
                emoteAction.accept(emote);
                event.accept();
                return;
            }
            super.contextMenuEvent(event);
        }
    }

    private String emoteImageAt(QTextDocument document, int position) {
        if (!chatEmotesEnabled || document == null || position < 0) return null;
        int[] candidates = {position, position - 1, position + 1};
        for (int candidate : candidates) {
            if (candidate < 0 || candidate >= document.characterCount()) continue;
            QTextCursor cursor = new QTextCursor(document);
            cursor.setPosition(candidate);
            cursor.movePosition(QTextCursor.MoveOperation.NextCharacter, QTextCursor.MoveMode.KeepAnchor);
            QTextCharFormat format = cursor.charFormat();
            cursor.dispose();
            try {
                if (!format.isImageFormat()) continue;
                QTextImageFormat imageFormat = format.toImageFormat();
                String name = imageFormat.name();
                imageFormat.dispose();
                if (name != null && name.startsWith("chat-emote:")) {
                    String code = name.substring("chat-emote:".length());
                    if (emotes.contains(code)) return code;
                }
            } finally {
                format.dispose();
            }
        }
        return null;
    }

    private String exactEmoteAt(String text, int position) {
        if (text == null || text.isEmpty() || position < 0 || position > text.length()) return null;
        int character = position == text.length() ? position - 1 : position;
        if (character < 0 || Character.isWhitespace(text.charAt(character))) return null;
        int start = character;
        while (start > 0 && !Character.isWhitespace(text.charAt(start - 1))) start--;
        int end = character + 1;
        while (end < text.length() && !Character.isWhitespace(text.charAt(end))) end++;
        String token = text.substring(start, end);
        return emotes.contains(token) ? token : null;
    }

    private final class ThreadTab {
        private final String rootId;
        private final QWidget panel;
        private final EmoteTranscript transcript;
        private final EmoteLineEdit input;
        private final String tabTitle;
        private final LinkedHashSet<String> seenIds = new LinkedHashSet<>();
        private final LinkedHashSet<String> optimisticMessages = new LinkedHashSet<>();
        private final List<ChatLine> displayedLines = new ArrayList<>();
        private final Set<String> displayedEmoteCodes = new HashSet<>();
        private long lastActivityAtMillis = System.currentTimeMillis();
        private String selectedReplyMessageId;
        private boolean replyTargetExplicitlySelected;
        private boolean involvesOwnUser;
        private boolean unread;

        private ThreadTab(
            String rootId,
            QWidget panel,
            EmoteTranscript transcript,
            EmoteLineEdit input,
            String tabTitle
        ) {
            this.rootId = rootId;
            this.panel = panel;
            this.transcript = transcript;
            this.input = input;
            this.tabTitle = tabTitle;
        }

        private void append(ChatLine line) {
            if (line == null) return;
            String author = line.author() == null ? "?" : line.author();
            String message = line.message() == null ? "[content unavailable]" : line.message();
            String identity = author + "\u0000" + message;
            if (line.id() != null) {
                if (!seenIds.add(line.id())) return;
                trimSet(seenIds, MAX_SEEN_IDS_PER_THREAD);
                if (!replyTargetExplicitlySelected) selectedReplyMessageId = line.id();
                if (optimisticMessages.remove(identity)) {
                    replaceLatestOptimisticLine(identity, line);
                    transcript.bindLatestUnassignedMessage(line.id());
                    registerActivity();
                    return;
                }
            }
            boolean mention = mentionsOwnUsername(message);
            boolean own = author.equalsIgnoreCase(ownUsername);
            if (mention || own) involvesOwnUser = true;
            appendTranscript(line, author, message, mention, own);
            registerActivity();
        }

        private void appendOptimistic(ChatLine line) {
            String author = line.author() == null ? "?" : line.author();
            String message = line.message() == null ? "" : line.message();
            String identity = author + "\u0000" + message;
            if (!optimisticMessages.add(identity)) return;
            trimSet(optimisticMessages, MAX_OPTIMISTIC_MESSAGES);
            involvesOwnUser = true;
            appendTranscript(line, author, message, false, true);
            registerActivity();
        }

        private void appendTranscript(ChatLine line, String author, String message, boolean mention, boolean own) {
            displayedLines.add(line);
            while (displayedLines.size() >= MAX_THREAD_LINES) displayedLines.remove(0);
            renderTranscriptLine(line, author, message, mention, own);
        }

        private void renderTranscriptLine(ChatLine line, String author, String message, boolean mention, boolean own) {
            QTextCursor cursor = transcript.textCursor();
            cursor.movePosition(QTextCursor.MoveOperation.End);
            QTextCharFormat messageFormat = new QTextCharFormat();
            messageFormat.setForeground(new QColor("#e4e9f0"));
            messageFormat.setBackground(messageBackgroundColor(line));
            if (mention) {
                messageFormat.setBackground(new QColor("#a67c00"));
                messageFormat.setForeground(new QColor("#fff4ba"));
            } else if (own) {
                messageFormat.setForeground(new QColor("#ffcf67"));
            }
            QTextCharFormat nicknameFormat = new QTextCharFormat(messageFormat);
            if (!mention) nicknameFormat.setForeground(nicknameColor(line));
            cursor.insertText(author + ":", nicknameFormat);
            cursor.insertText(" ", messageFormat);
            insertMessageWithEmotes(cursor, message, messageFormat);
            displayedEmoteCodes.addAll(emoteCodesIn(message));
            cursor.insertText("\n", messageFormat);
            transcript.setTextCursor(cursor);
            transcript.registerMessage(line.id());
            transcript.ensureCursorVisible();
        }

        private void replaceLatestOptimisticLine(String identity, ChatLine replacement) {
            for (int index = displayedLines.size() - 1; index >= 0; index--) {
                ChatLine candidate = displayedLines.get(index);
                String candidateAuthor = candidate.author() == null ? "?" : candidate.author();
                String candidateMessage = candidate.message() == null ? "" : candidate.message();
                if ((candidateAuthor + "\u0000" + candidateMessage).equals(identity) && candidate.id() == null) {
                    displayedLines.set(index, replacement);
                    return;
                }
            }
        }

        private void refreshTranscript() {
            boolean followBottom = transcript.verticalScrollBar().value()
                >= transcript.verticalScrollBar().maximum() - 4;
            int previousScroll = transcript.verticalScrollBar().value();
            transcript.clear();
            transcript.clearRegisteredMessages();
            displayedEmoteCodes.clear();
            for (ChatLine line : displayedLines) {
                String author = line.author() == null ? "?" : line.author();
                String message = line.message() == null ? "[content unavailable]" : line.message();
                renderTranscriptLine(
                    line,
                    author,
                    message,
                    mentionsOwnUsername(message),
                    author.equalsIgnoreCase(ownUsername)
                );
            }
            if (followBottom) {
                transcript.verticalScrollBar().setValue(transcript.verticalScrollBar().maximum());
            } else {
                transcript.verticalScrollBar().setValue(
                    Math.min(previousScroll, transcript.verticalScrollBar().maximum())
                );
            }
        }

        private void updateEmoteFrame(String code, QPixmap pixmap) {
            if (!displayedEmoteCodes.contains(code)) return;
            updateDocumentEmoteFrame(transcript.document(), code, pixmap);
            transcript.viewport().update();
        }

        private void selectReplyTarget(String messageId) {
            selectedReplyMessageId = messageId;
            replyTargetExplicitlySelected = true;
            focusInput();
        }

        private String replyTargetId() {
            return selectedReplyMessageId == null ? rootId : selectedReplyMessageId;
        }

        private void focusInput() {
            input.setFocus();
        }

        private void registerActivity() {
            lastActivityAtMillis = System.currentTimeMillis();
            if (tabs.currentWidget() != panel) unread = true;
            refreshAppearance(lastActivityAtMillis, Duration.ofMinutes(30).toMillis());
        }

        private void markRead() {
            if (unread) {
                unread = false;
                refreshAppearance(System.currentTimeMillis(), Duration.ofMinutes(30).toMillis());
            }
        }

        private void refreshAppearance(long now, long redAfter) {
            int index = tabs.indexOf(panel);
            if (index < 0) return;
            boolean old = !involvesOwnUser && now - lastActivityAtMillis >= redAfter;
            tabs.setTabText(index, unread ? "● " + tabTitle : tabTitle);
            tabs.setTabTextColor(index, old ? new QColor("#ff5f67")
                : involvesOwnUser ? new QColor("#ffae42")
                : unread ? new QColor("#ffe27a") : new QColor("#b7c0cc"));
            tabs.setTabToolTip(index, involvesOwnUser
                ? "This thread mentions or includes " + ownUsername + " — it will not close automatically"
                : unread ? "New unread message" : "Thread " + rootId);
        }

    }

    private final class EmoteLineEdit extends QLineEdit {
        private List<String> matches;
        private List<BilingualSpellChecker.Issue> spellingIssues = List.of();
        private List<String> spellingSuggestions;
        private int matchIndex;
        private int completionStart = -1;
        private int spellingSuggestionIndex;
        private int spellingCompletionStart = -1;
        private int spellCheckGeneration;
        private String lastInserted = "";
        private String lastSpellingInsertion = "";
        private boolean replacingSpellingCompletion;
        private boolean sentMessageHistoryEnabled;
        private int historyIndex = -1;
        private String historyDraft = "";
        private final QTimer spellCheckDebounce;
        private Runnable submitAction = () -> {};

        private EmoteLineEdit() {
            spellCheckDebounce = new QTimer(this);
            spellCheckDebounce.setSingleShot(true);
            spellCheckDebounce.setInterval(450);
            spellCheckDebounce.timeout.connect(this::requestSpellCheck);
            textChanged.connect(() -> {
                if (!replacingSpellingCompletion) resetSpellingCompletion();
                spellingIssues = List.of();
                spellCheckDebounce.start();
            });
        }

        private void setSubmitAction(Runnable action) {
            submitAction = action == null ? () -> {} : action;
        }

        private void enableSentMessageHistory() {
            sentMessageHistoryEnabled = true;
        }

        private void resetHistoryNavigation() {
            historyIndex = -1;
            historyDraft = "";
        }

        private void submit() {
            submitAction.run();
        }

        @Override
        public boolean event(QEvent event) {
            if (event.type() == QEvent.Type.FocusIn) {
                activeComposerInput = this;
            }
            if (event.type() == QEvent.Type.KeyPress
                && event instanceof QKeyEvent keyEvent
                && keyEvent.key() == Qt.Key.Key_Tab.value()) {
                boolean completed;
                if (activeSpellingSuggestionsExhausted()) {
                    resetSpellingCompletion();
                    completed = completeChatToken();
                } else {
                    completed = completeSpellingSuggestion();
                    if (!completed) completed = completeChatToken();
                }
                if (!completed) {
                    setToolTip("No writing suggestion, emote, or username match");
                    QApplication.beep();
                }
                keyEvent.accept();
                return true;
            }
            if (event.type() == QEvent.Type.KeyPress
                && event instanceof QKeyEvent keyEvent
                && (keyEvent.key() == Qt.Key.Key_Return.value()
                    || keyEvent.key() == Qt.Key.Key_Enter.value())) {
                submit();
                keyEvent.accept();
                return true;
            }
            if (event.type() == QEvent.Type.KeyPress
                && event instanceof QKeyEvent keyEvent
                && sentMessageHistoryEnabled
                && keyEvent.key() == Qt.Key.Key_Up.value()) {
                if (showOlderSentMessage()) {
                    keyEvent.accept();
                    return true;
                }
            }
            if (event.type() == QEvent.Type.KeyPress
                && event instanceof QKeyEvent keyEvent
                && sentMessageHistoryEnabled
                && keyEvent.key() == Qt.Key.Key_Down.value()) {
                if (showNewerSentMessage()) {
                    keyEvent.accept();
                    return true;
                }
            }
            if (event.type() == QEvent.Type.KeyPress && historyIndex >= 0) {
                resetHistoryNavigation();
            }
            return super.event(event);
        }

        private boolean showOlderSentMessage() {
            if (sentMessageHistory.isEmpty()) return false;
            if (historyIndex < 0) {
                historyDraft = text();
                historyIndex = sentMessageHistory.size() - 1;
            } else if (historyIndex > 0) {
                historyIndex--;
            }
            showHistoryText(sentMessageHistory.get(historyIndex));
            return true;
        }

        private boolean showNewerSentMessage() {
            if (historyIndex < 0) return false;
            if (historyIndex < sentMessageHistory.size() - 1) {
                historyIndex++;
                showHistoryText(sentMessageHistory.get(historyIndex));
            } else {
                String draft = historyDraft;
                resetHistoryNavigation();
                showHistoryText(draft);
            }
            return true;
        }

        private void showHistoryText(String value) {
            setText(value);
            setCursorPosition(value.length());
        }

        private boolean completeChatToken() {
            int caret = cursorPosition();
            String value = text();
            int start = caret;
            while (start > 0 && !Character.isWhitespace(value.charAt(start - 1))) start--;
            String current = value.substring(start, caret);
            if (matches != null && completionStart == start && current.equals(lastInserted)) {
                matchIndex = (matchIndex + 1) % matches.size();
            } else {
                matches = ThreadViewer.this.completeChatToken(current);
                matchIndex = 0;
                completionStart = start;
            }
            if (matches == null || matches.isEmpty()) {
                matches = null;
                return false;
            }
            String completion = matches.get(matchIndex);
            setSelection(start, caret - start);
            insert(completion);
            lastInserted = completion;
            setToolTip("Tab: " + (matchIndex + 1) + "/" + matches.size() + " — " + completion);
            return true;
        }

        private boolean completeSpellingSuggestion() {
            int caret = cursorPosition();
            String value = text();
            int replacementEnd;

            if (spellingSuggestions != null
                && spellingCompletionStart >= 0
                && caret == spellingCompletionStart + lastSpellingInsertion.length()
                && value.regionMatches(
                    spellingCompletionStart,
                    lastSpellingInsertion,
                    0,
                    lastSpellingInsertion.length()
                )) {
                spellingSuggestionIndex = (spellingSuggestionIndex + 1) % spellingSuggestions.size();
                replacementEnd = caret;
            } else {
                BilingualSpellChecker.Issue issue = spellingIssues.stream()
                    .filter(candidate -> caret >= candidate.start() && caret <= candidate.end())
                    .filter(candidate -> !candidate.suggestions().isEmpty())
                    .findFirst()
                    .orElse(null);
                if (issue == null) return false;
                spellingSuggestions = issue.suggestions();
                spellingSuggestionIndex = 0;
                spellingCompletionStart = issue.start();
                replacementEnd = issue.end();
            }

            String replacement = spellingSuggestions.get(spellingSuggestionIndex);
            replacingSpellingCompletion = true;
            try {
                setSelection(spellingCompletionStart, replacementEnd - spellingCompletionStart);
                insert(replacement);
            } finally {
                replacingSpellingCompletion = false;
            }
            lastSpellingInsertion = replacement;
            setToolTip(
                "Tab — writing suggestion: " + (spellingSuggestionIndex + 1) + "/"
                    + spellingSuggestions.size() + " — " + replacement
            );
            return true;
        }

        private boolean activeSpellingSuggestionsExhausted() {
            if (spellingSuggestions == null
                || spellingSuggestions.isEmpty()
                || spellingSuggestionIndex < spellingSuggestions.size() - 1
                || spellingCompletionStart < 0) {
                return false;
            }
            int caret = cursorPosition();
            String value = text();
            return caret == spellingCompletionStart + lastSpellingInsertion.length()
                && value.regionMatches(
                    spellingCompletionStart,
                    lastSpellingInsertion,
                    0,
                    lastSpellingInsertion.length()
                );
        }

        private void resetSpellingCompletion() {
            spellingSuggestions = null;
            spellingSuggestionIndex = 0;
            spellingCompletionStart = -1;
            lastSpellingInsertion = "";
        }

        private void requestSpellCheck() {
            String text = text();
            int generation = ++spellCheckGeneration;
            spellChecker.checkAsync(text, issues -> enqueueUi(() -> {
                if (generation != spellCheckGeneration || !text.equals(text())) return;
                spellingIssues = issues.stream()
                    .filter(issue -> shouldShowSpellingIssue(text, issue))
                    .toList();
                update();
                if (!spellingIssues.isEmpty()) {
                    setToolTip("Tab or right-click: spelling or grammar suggestions");
                }
            }));
        }

        private boolean shouldShowSpellingIssue(String text, BilingualSpellChecker.Issue issue) {
            if (issue.start() < 0 || issue.end() > text.length() || issue.start() >= issue.end()) return false;
            String word = text.substring(issue.start(), issue.end());
            if (emotes.contains(word) || !emotes.complete(word).isEmpty() || isKnownUsername(word)) return false;
            if (issue.start() > 0 && text.charAt(issue.start() - 1) == '@') return false;
            return !word.contains("://");
        }

        @Override
        protected void paintEvent(QPaintEvent event) {
            super.paintEvent(event);
            if (spellingIssues.isEmpty()) return;

            String value = text();
            int caret = Math.max(0, Math.min(cursorPosition(), value.length()));
            var caretBox = cursorRect();
            QFontMetrics metrics = new QFontMetrics(font());
            QColor color = new QColor("#ff5f67");
            QPen pen = new QPen(color, 1.2);
            QPainter painter = new QPainter(this);
            try {
                painter.setPen(pen);
                int textOrigin = caretBox.x() - metrics.horizontalAdvance(value.substring(0, caret));
                int baseline = Math.min(height() - 2, caretBox.bottom() + 1);
                for (BilingualSpellChecker.Issue issue : spellingIssues) {
                    if (issue.start() < 0 || issue.end() > value.length() || issue.start() >= issue.end()) continue;
                    int startX = textOrigin + metrics.horizontalAdvance(value.substring(0, issue.start()));
                    int endX = textOrigin + metrics.horizontalAdvance(value.substring(0, issue.end()));
                    drawSpellingWave(painter, startX, endX, baseline);
                }
            } finally {
                painter.end();
                painter.dispose();
                pen.dispose();
                color.dispose();
                metrics.dispose();
                caretBox.dispose();
            }
        }

        private void drawSpellingWave(QPainter painter, int startX, int endX, int baseline) {
            int left = Math.max(2, startX);
            int right = Math.min(width() - 2, endX);
            for (int x = left; x < right; x += 4) {
                int middle = Math.min(x + 2, right);
                int end = Math.min(x + 4, right);
                painter.drawLine(x, baseline, middle, baseline - 2);
                painter.drawLine(middle, baseline - 2, end, baseline);
            }
        }

        @Override
        protected void contextMenuEvent(QContextMenuEvent event) {
            int position = cursorPositionAt(event.pos());
            BilingualSpellChecker.Issue issue = spellingIssues.stream()
                .filter(candidate -> position >= candidate.start() && position <= candidate.end())
                .findFirst()
                .orElse(null);
            QMenu menu = createStandardContextMenu();
            if (issue != null && !issue.suggestions().isEmpty()) {
                menu.insertSeparator(menu.actions().isEmpty() ? null : menu.actions().get(0));
                for (int index = issue.suggestions().size() - 1; index >= 0; index--) {
                    String suggestion = issue.suggestions().get(index);
                    var before = menu.actions().isEmpty() ? null : menu.actions().get(0);
                    var action = new io.qt.gui.QAction(suggestion, menu);
                    action.triggered.connect(() -> replaceSpellingIssue(issue, suggestion));
                    menu.insertAction(before, action);
                }
            }
            menu.exec(event.globalPos());
            menu.disposeLater();
        }

        private void replaceSpellingIssue(BilingualSpellChecker.Issue issue, String replacement) {
            resetSpellingCompletion();
            setSelection(issue.start(), issue.end() - issue.start());
            insert(replacement);
        }
    }

    private static void trimSet(LinkedHashSet<String> values, int maximumSize) {
        while (values.size() > maximumSize) {
            var iterator = values.iterator();
            if (!iterator.hasNext()) return;
            iterator.next();
            iterator.remove();
        }
    }
}
