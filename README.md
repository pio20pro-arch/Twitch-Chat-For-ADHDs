# Twitch Chat for ADHDs
Created as proof of concept.
This program shows one Twitch channel in a separate, lightweight window. It helps users stay focused on reading and offers a wide range of customizable features.
It is not perfect but good enouth to play with

<img width=40% height=40% alt="image" src="https://github.com/user-attachments/assets/47084054-1a75-431b-b881-7eb659de1ae0" />

## How to start the application

### What you need

You need:

- Windows,
- an internet connection,
- Java 17 or newer,
- a Twitch account used by the application,
- an OAuth token for that same account.

Java must be available in the system as `java.exe` and `javaw.exe`. If you have just installed Java, close and reopen File Explorer or restart the computer.

### First start

1. Open the project folder.
2. Open `config.properties` in Notepad or VS Code.
3. Find these three settings:

   ```properties
   channel=channel_name
   bot.username=bot_account_name
   oauth.token=oauth_token
   ```

4. Enter your details:

   - `channel` — the channel you want to watch, for example `jinnytty`. You can also paste the full Twitch channel URL;
   - `bot.username` — the account that will send messages;
   - `oauth.token` — an OAuth token belonging to the account above. The token may start with `oauth:`, but it does not have to.

5. Save `config.properties`.
6. Double-click `run.vbs`.
7. Wait for the **Twitch Chat for ADHDs** window to appear.

Example:

```properties
channel=some_channel
bot.username=my_bot_account
oauth.token=oauth:xxxxxxxxxxxxxxxx
```

The first start can take a few minutes. The application downloads the required libraries and builds the current version before showing the window. No console window is displayed during this process. Do not click `run.vbs` repeatedly—wait for the first start to finish.

The token must allow reading and sending chat messages (`chat:read` and `chat:edit`). It is stored as plain text, so never share or publish `config.properties`.

### Every later start

After the first setup, you only need to:

1. open the project folder,
2. double-click `run.vbs`,
3. wait for the application window.

`run.vbs` is the normal way to start the application. `run.bat` is an alternative shortcut that starts exactly the same process. You do not need to run Gradle manually or open anything inside the `build` directory.

Before showing the window, the launcher checks that the program and its libraries are up to date. You therefore keep using the same `run.vbs` file after changing the code or adding a dictionary.

### Closing and restarting

- Close the main window with the `X` button. This disconnects the application from Twitch and stops all its tasks.
- To restart it, wait a moment after closing and double-click `run.vbs` again.
- Do not start several copies. The program blocks a second copy using the same account and channel.

### If nothing appears after double-clicking

`run.vbs` works without a console, so an error will not appear in a separate black window. Check:

1. `logs/application.log` — whether the build started and where it stopped;
2. `logs/error.log` — whether Java or the application reported an error;
3. that your internet connection works;
4. that Java 17 or newer is installed;
5. that the `qt` folder is still inside the project folder;
6. that `channel`, `bot.username`, and `oauth.token` are filled in.

If the application is already running, clicking `run.vbs` again will not open another window.

## Important files

| File | Purpose |
|---|---|
| `config.properties` | Twitch connection, appearance, filters, watched and excluded users, emotes, and dictionaries. |
| `greetings.properties` | Greeting and goodbye texts and the list of users who may be greeted. |
| `chatbot-spammer.properties` | Phrases that can trigger automatic responses. |
| `run.vbs` | Normal start without a console window. |
| `run.bat` | Alternative start shortcut. |
| `logs/application.log` | Information about application activity and sent responses. |
| `logs/error.log` | Connection, configuration, and sending errors. |
| `cache/emotes` | The emote index and downloaded images. This is not a chat archive. |

## What is in the window

### Full Chat

This is the main channel chat.

- Clicking a message opens its thread and focuses the reply field.
- A message addressed to you has a white border.
- Messages from watched users have a green background.
- Messages sent by your account have a separate highlight.
- Nicknames use the color supplied by Twitch. If a user has no color, the application gives them a consistent generated color.
- Hovering over a recognized emote shows a larger preview.
- Right-clicking a recognized emote sends it to the main chat.
- Scrolling up stops automatic scrolling. Automatic scrolling resumes after you return to the bottom.

The field below the chat sends a normal public message. Use the **Send** button or press `Enter`.

### Watched

The **Watched** tab shows messages from users listed under `watched.users=` in `config.properties`. Clicking a message opens its thread. Filters used by **Full Chat** do not remove entries from this separate tab.

### Sent by me

The **Sent by me** tab shows messages successfully sent by the application:

- manual messages,
- thread replies,
- greetings,
- goodbye messages,
- Chatbox Spammer responses.

This is not a complete archive of the Twitch account. It only shows messages sent by the currently running application.

### Threads

Each open thread gets its own tab. **Watched** and **Sent by me** always remain first. A new thread is inserted immediately after them, so newer threads appear before older ones. When there is not enough horizontal space, tab buttons wrap onto additional rows.

- Clicking a specific message inside a thread selects it as the reply target.
- The selected message becomes highlighted.
- Press `Enter` or click **Reply** to reply to the selected message.
- Right-clicking an emote inside a thread sends it as a reply in that thread.
- Hovering over an emote shows its preview.
- A `●` before the tab name means that it contains a new unread message.
- An orange tab contains a mention of you or a message from your account. It will not close automatically.
- A normal tab becomes red after 30 minutes without activity and closes automatically after 35 minutes.
- Every new message in a thread restarts that timer.
- The `×` button closes a thread and removes its stored messages from application memory.
- The application keeps at most 50 thread tabs. After reaching the limit, it closes the oldest inactive tab.

`thread.auto.open=watched` automatically opens threads connected to watched users. `thread.auto.open=all` opens every detected thread. A thread containing a message addressed to you opens regardless of this setting.

### Bottom bar

The bottom bar contains:

- the application name,
- the channel name,
- connection status,
- **Greetings: ON/OFF** — enables or disables automatic greetings,
- **Goodbye: ON/OFF** — starts one goodbye series; the application remains open after it finishes.

### Chatters — optional moderator column

`mod.mode=on` adds a column containing users connected to chat. Twitch Helix refreshes this list every two minutes.

This feature requires:

- moderator status in the watched channel,
- the `moderator:read:chatters` token scope,
- the correct `client.id` of the Twitch application that issued the token.

With `mod.mode=off`, the column is removed and no Chatters requests are made. Changing this setting requires a restart.

## Writing messages and keyboard shortcuts

| Key or action | Result |
|---|---|
| `Enter` | Sends text from the active input field. |
| `↑` in the main input | Restores an older manually sent message. |
| `↓` in the main input | Moves to a newer message or restores the draft you were writing. |
| `Tab` on a misspelled word | Inserts the next dictionary suggestion. |
| `Tab` after the beginning of an emote code | Completes an emote that starts with those characters. |
| `Tab` after the beginning of a nickname | Completes a nickname seen in chat. A token starting with `@` searches only usernames. |
| `Ctrl+F` | Focuses the emote search field. |
| Right-click on a misspelled word | Shows dictionary suggestions and normal copy/paste actions. |

Emote completion ignores case while searching but inserts the real code with its correct capitalization. Recognizing a finished emote is case-sensitive: `LuL` and `LUL` can be different codes.

If a word is misspelled and also looks like the beginning of an emote, `Tab` goes through dictionary corrections first and then emotes and usernames.

## Emote column

`emotes.column=on` shows the emote column on the left. `off` removes it completely.

- **Search emotes…** finds emotes containing the entered text.
- `Ctrl+F` focuses this field from anywhere in the window.
- Clicking a tile inserts its code into the last active input: the main chat or the active thread.
- After moving to the list with `Tab`, use the arrow keys to select a tile. `Enter` or `Space` inserts it.
- Hovering over a tile shows a `2x` preview near the pointer.
- **Animation: ON/OFF** starts or stops animations in both the emote picker and chat messages. Only visible picker tiles are animated to avoid unnecessary CPU use.
- `emotes.icon.size` sets the tile size from 20 to 64 pixels.

### Emotes inside chat messages

Control graphical emotes in `config.properties`:

```properties
emotes.chat=on
```

- `on` replaces recognized emote codes with their native `1x` images in **Full Chat**, **Watched**, and thread messages.
- `off` leaves recognized emote codes as bold text.
- Named-emote recognition remains case-sensitive.
- Animated message emotes use the same **Animation: ON/OFF** button as the picker. When animation is off, a still frame remains visible.
- Hovering over an emote in a message still opens its larger preview.
- Emote images use the disk cache in `cache/emotes`; already downloaded images are not downloaded again on every start.

Changing `emotes.chat` requires restarting the application.

The application loads Twitch, 7TV, BetterTTV, and FrankerFaceZ emotes. Names and image addresses are saved in `cache/emotes/catalog-channel_name.json`. The index remains fresh for 24 hours and allows a known catalog to load without internet access. Images are also saved in `cache/emotes`, so they do not need to be downloaded on every start.

## Spelling and grammar checking

Select active dictionaries in `config.properties`:

```properties
spellcheck.languages=en-US,pl-PL
```

You can add another code, for example:

```properties
spellcheck.languages=en-US,pl-PL,de-DE,fr
```

After restarting through `run.vbs`, Gradle automatically downloads a missing dictionary. Changing this setting requires a restart. An empty value disables spell checking:

```properties
spellcheck.languages=
```

A word is marked as misspelled only when none of the active spelling dictionaries recognizes it. Emotes, known usernames, mentions beginning with `@`, and internet addresses are not marked as mistakes.

Grammar rules are configured separately. Chinese and Japanese LanguageTool modules contain grammar rules but no spelling dictionary, so the default configuration uses them only here:

```properties
grammar.languages=ja,zh
```

Grammar findings use the same red underline, `Tab` completion, and right-click suggestion menu as spelling findings. An empty value disables grammar checking:

```properties
grammar.languages=
```

Spelling and grammar checks run away from the window thread. Both settings require restarting through `run.vbs`; Gradle reads both lists and downloads the required LanguageTool modules before startup.

Available LanguageTool modules: `ar`, `ast`, `be`, `br`, `ca`, `crh`, `da`, `de`, `el`, `en`, `eo`, `es`, `fa`, `fr`, `ga`, `gl`, `is`, `it`, `ja`, `km`, `lt`, `ml`, `nl`, `pl`, `pt`, `ro`, `ru`, `sk`, `sl`, `sv`, `ta`, `tl`, `uk`, `zh`.

A LanguageTool module does not always include a spelling dictionary. Chinese (`zh`) and Japanese (`ja`) therefore belong in `grammar.languages`, not `spellcheck.languages`. Korean (`ko`) is not available as a LanguageTool module and cannot currently be checked. Khmer (`km`) and Tagalog (`tl`) provide spelling rules. The application ignores a spelling language that has no spelling rules instead of allowing it to suppress results from valid dictionaries.

`chat.filter.languages` is independent of both writing checks: it recognizes writing systems in received chat messages and does not use LanguageTool.

## Full Chat filters

Filters only hide messages in **Full Chat**. They do not delete anything from Twitch, do not remove messages from threads, and do not affect the **Watched** tab. Messages sent by your own account always remain visible.

Choose filtered languages in one comma-separated setting, in the same style as `spellcheck.languages`:

```properties
chat.filter.languages=zh,ja,ko
```

An empty value disables every language/script filter:

```properties
chat.filter.languages=
```

| Setting | Simple meaning |
|---|---|
| `chat.filter.emote.only=true` | Hides messages containing only recognized emotes, including phone emoji. |
| `chat.filter.languages=zh,ja,ko` | Hides messages using the writing systems associated with the listed language codes. Separate codes with commas or semicolons. |
| `chat.filter.contains=text1;text2` | Hides a message containing any listed text fragment. |
| `chat.filter.min.length=4` | Hides messages shorter than four characters. `0` disables this filter. |

Common filter codes:

| Codes | Writing system that is filtered |
|---|---|
| `zh` | Han characters without Japanese kana |
| `ja` | Japanese hiragana and katakana |
| `ko` | Korean Hangul |
| `ar`, `fa`, `ur` | Arabic script |
| `ru`, `uk`, `be`, `bg`, `mk` | Cyrillic |
| `el` | Greek |
| `he`, `yi` | Hebrew |
| `th` | Thai |
| `hi`, `mr`, `ne` | Devanagari |
| `bn` | Bengali |
| `ta` | Tamil |
| `km` | Khmer |
| `my`, `lo`, `si` | Myanmar, Lao, Sinhala respectively |
| `te`, `kn`, `ml`, `gu`, `pa` | Telugu, Kannada, Malayalam, Gujarati, Gurmukhi respectively |
| `ka`, `hy`, `am`, `ti` | Georgian, Armenian, or Ethiopic |
| `en`, `pl`, `de`, `fr`, `es`, `pt`, `it`, etc. | The entire Latin script, not one specific language |

You can also enter a Java Unicode script name directly, for example `arabic`, `cyrillic`, `greek`, `hebrew`, `thai`, `devanagari`, `tamil` or `khmer`.

Recognition is intentionally lightweight and based on writing systems rather than statistical language detection. Languages sharing an alphabet are therefore filtered together: adding `en` or `pl`, for example, hides all Latin-script messages. Chinese means Han characters without Japanese kana; Japanese means hiragana or katakana. Japanese text written only in kanji may consequently be treated as Chinese.

Recognized emote names are ignored while checking scripts, so an emote code is not incorrectly treated as ordinary Latin text. The old `chat.filter.chinese`, `chat.filter.japanese`, `chat.filter.korean`, and `chat.filter.latin` switches remain accepted for compatibility.

Filter changes are applied automatically after saving `config.properties`, including to messages already visible in the application. Restarting is not required.

## Watched users, excluded users, and your aliases

### Watched users

Enter one login per line below `watched.users=`:

```properties
watched.users=
user_one
user_two
```

Watched users have highlighted messages and appear in the **Watched** tab. This list does not control greetings—`allowed.users=` in `greetings.properties` does that.

### Excluded users

Enter one login per line below `excluded.users=`:

```properties
excluded.users=
StreamElements
another_bot
```

Messages from excluded users are hidden from Full Chat. These users receive no automatic greeting or goodbye, and their threads do not open automatically. They can still trigger a Chatbox Spammer rule.

### Alternative forms of your nickname

If people shorten or misspell your nickname, add those forms separated by commas or semicolons:

```properties
bot.mention.aliases=Bird,Birdy
```

The application always recognizes the full `bot.username` as well. Alias matching ignores case but requires a complete word. A recognized message gets a white border and can automatically open a protected thread.

## Greetings

Greetings are off after startup. Click **Greetings: OFF** to enable them.

Greeting settings are stored in `greetings.properties`:

```properties
mode=defined

messages=
Hello @{user}
Yo @{user}

allowed.users=
user_one
user_two
```

- `mode=all` allows every new user to be greeted.
- `mode=defined` allows only users listed under `allowed.users=`.
- `messages=` contains random greeting texts, one per following line.
- `{user}` and `@{user}` are replaced with the greeted user's login.
- `toggle.command=hi` is the preserved configuration name of this function; use the window button to control it.

After successfully greeting someone, the application adds:

```properties
greeted.user.login=true
```

This prevents the same user from being greeted again after a restart. Greetings use the `response.delay.*` delay and shared `response.interval.*`, but they are not affected by `response.chance`.

A Twitch reply belonging to an existing thread does not trigger a greeting.

## Goodbye

The **Goodbye: OFF** button performs one goodbye series:

1. It disables new greetings.
2. It reads users saved as `greeted.user.*=true`.
3. It skips users from `excluded.users=`.
4. It sorts logins alphabetically.
5. It divides them into messages containing at most 16 users.
6. It selects a separate random text from `farewell.messages=` for each group.
7. After successfully sending a group, it removes those users from `greetings.properties`.
8. It finishes the Goodbye operation but keeps the application running.

Example:

```properties
farewell.messages=
bye bye
thank you for today
see you next time
```

When at least two texts exist, the same text is never used for two consecutive groups. The application normally appends user mentions at the end. Use `{users}` to select their position:

```properties
farewell.messages=
Good night {users} see you next time
```

Users from a failed group remain saved, so the application can try to say goodbye to them later.

## Chatbox Spammer

`chatbot-spammer.properties` defines automatic responses. The simplest rule looks like this:

```properties
hello=Hi @{user}!
```

The left side is the phrase to find. The right side is the response.

- Matching is case-sensitive: `LUL` does not match `lul`.
- A trigger must be a complete word or phrase. `hey` does not match inside `heyyy`.
- `{user}` inserts the author's login.
- `{message}` inserts the author's complete message.
- A line beginning with `#` is a comment.
- The first matching rule stops further rule checks for that message.
- One chat message can trigger at most one automatic response.
- Twitch replies that belong to threads do not trigger Chatbox Spammer.

Separate several possible responses with `|`:

```properties
hello=Hi @{user}|Hello @{user}|Hey @{user}
```

The application chooses one variant randomly.

### Counting several occurrences

```properties
{2|5000|3}LUL=LUL
```

This rule means:

- wait for 2 occurrences of `LUL`,
- both must appear within 5000 ms,
- the response may contain from 1 to 3 repetitions, for example `LUL LUL`.

After reaching the required count, the counter starts again from zero.

### Recognized emotes without a custom response

An entry such as:

```properties
Garbedge=Garbedge
```

is a normal rule: after detecting the emote, the program may send the same emote. The response chance, delay, shared interval, and duplicate cooldown still apply.

## All `config.properties` settings

| Setting | Purpose | Restart required? |
|---|---|---|
| `channel` | Twitch channel without `#`, or the full channel URL. | Yes |
| `bot.username` | Account used to send messages. | Yes |
| `bot.mention.aliases` | Alternative forms of your nickname. | No |
| `oauth.token` | Secret account token. | Yes |
| `client.id` | Twitch application ID required by Chatters. | Yes |
| `mod.mode` | Shows or removes the Chatters column. | Yes |
| `emotes.column` | Shows or removes the emote column. | Yes |
| `emotes.animation` | Initial animation state for the picker and graphical emotes in messages. | Yes |
| `emotes.chat` | Shows recognized emotes as images in Full Chat, Watched, and thread messages. | Yes |
| `emotes.icon.size` | Emote tile size from 20 to 64 px. | Yes |
| `spellcheck.languages` | List of active dictionaries. | Yes |
| `grammar.languages` | List of LanguageTool grammar-rule languages, such as `ja,zh`. | Yes |
| `response.chance` | Chatbox Spammer response chance; `0.35` means 35%. | No |
| `response.delay.min.ms` | Shortest random response and greeting delay. | No |
| `response.delay.max.ms` | Longest random response and greeting delay. | No |
| `response.interval.min.ms` | Shortest shared interval between automatic messages. | No |
| `response.interval.max.ms` | Longest shared interval between automatic messages. | No |
| `response.same.cooldown.ms` | How long Spammer must wait before sending the same response again. | No |
| `chat.filter.emote.only` | Filter for messages containing only emotes. | No |
| `chat.filter.languages` | Comma-separated language codes or Unicode writing systems hidden from Full Chat. | No |
| `chat.filter.contains` | List of hidden text fragments. | No |
| `chat.filter.min.length` | Minimum displayed message length. | No |
| `chat.font.size` | Chat font size from 8 to 24 points. | No |
| `chat.line.spacing` | Space between messages from 0 to 20 px. | No |
| `thread.auto.open` | Automatic thread mode: `watched` or `all`. | No |
| `watched.users` | List of watched users. | No |
| `excluded.users` | List of hidden and skipped users. | No |
| `greeting.config.file` | Name of the greetings file. | Yes |
| `spammer.config.file` | Name of the Spammer rules file. | Yes |

`response.interval.*` is shared by Chatbox Spammer, Greetings, and Goodbye. This prevents those features from trying to send messages at the same time.

## Changes applied without restarting

After saving a file, the application automatically reloads:

- rules from `chatbot-spammer.properties`,
- texts and lists from `greetings.properties`,
- filters, chat appearance, watched users, excluded users, aliases, and `response.*` settings from `config.properties`.

If an edited file contains an error, the application keeps the last valid configuration and writes the problem to `logs/error.log`.

A restart is required after changing connection details, moderator mode, the emote column, initial animation state, emote tile size, dictionary list, or configuration file names.

## Memory, saved data, and performance

- Full Chat is kept in memory for 30 minutes and displays at most 1500 lines.
- The application remembers at most 10000 message IDs needed to build threads.
- One thread displays at most 500 lines.
- Watched displays at most 500 messages.
- Sent by me displays at most 500 entries.
- The `↑` and `↓` history remembers 100 manually sent messages.
- Chat messages are not saved to disk as a chat archive.
- Logs, configuration, and the emote cache remain on disk.
- Receiving, filtering, spell checking, emote downloading, and sending work outside the main window event handling so a busy chat interferes with typing as little as possible.
- Only one copy may run for the same account and channel pair.

## Troubleshooting

1. Check `logs/error.log`.
2. Check the latest entries in `logs/application.log`.
3. Make sure `channel`, `bot.username`, and `oauth.token` refer to the correct accounts.
4. If Chatters does not work, check moderator status, the token scope, and `client.id`.
5. If a new dictionary is not downloaded, start `run.vbs` with internet access and check the application log.
6. If you changed an option marked as requiring a restart, close the complete application window and start it again.

Closing the main window ends the Twitch connection and stops all application tasks.
