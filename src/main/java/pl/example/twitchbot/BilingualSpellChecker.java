package pl.example.twitchbot;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import org.languagetool.JLanguageTool;
import org.languagetool.Languages;
import org.languagetool.rules.Rule;
import org.languagetool.rules.RuleMatch;
import org.languagetool.rules.spelling.SpellingCheckRule;

/** Configurable local spelling and grammar checker. Work is serialized away from the Qt UI thread. */
final class BilingualSpellChecker implements AutoCloseable {
    private final ExecutorService executor = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "spell-checker");
        thread.setDaemon(true);
        return thread;
    });
    private final List<String> spellingLanguageCodes;
    private final List<String> grammarLanguageCodes;
    private List<JLanguageTool> spellingTools;
    private List<JLanguageTool> grammarTools;

    BilingualSpellChecker(List<String> spellingLanguageCodes, List<String> grammarLanguageCodes) {
        this.spellingLanguageCodes = normalizedCodes(spellingLanguageCodes);
        this.grammarLanguageCodes = normalizedCodes(grammarLanguageCodes);
    }

    void checkAsync(String text, Consumer<List<Issue>> resultConsumer) {
        executor.execute(() -> {
            try {
                resultConsumer.accept(check(text));
            } catch (RuntimeException | IOException exception) {
                System.err.println("Spelling or grammar check failed: " + exception.getMessage());
                resultConsumer.accept(List.of());
            }
        });
    }

    private List<Issue> check(String text) throws IOException {
        if (text == null || text.isBlank()) return List.of();
        ensureInitialized();
        Map<Span, LinkedHashSet<String>> issuesBySpan = new LinkedHashMap<>();
        List<Map<Span, RuleMatch>> matchesByLanguage = new ArrayList<>(spellingTools.size());
        for (JLanguageTool languageTool : spellingTools) {
            matchesByLanguage.add(spellingMatches(languageTool, text));
        }
        if (!matchesByLanguage.isEmpty()) {
            for (Map.Entry<Span, RuleMatch> entry : matchesByLanguage.get(0).entrySet()) {
                boolean misspelledInEveryLanguage = matchesByLanguage.stream()
                    .skip(1)
                    .allMatch(matches -> matches.containsKey(entry.getKey()));
                if (!misspelledInEveryLanguage) continue;
                LinkedHashSet<String> suggestions = issuesBySpan.computeIfAbsent(
                    entry.getKey(),
                    ignored -> new LinkedHashSet<>()
                );
                for (Map<Span, RuleMatch> matches : matchesByLanguage) {
                    suggestions.addAll(matches.get(entry.getKey()).getSuggestedReplacements());
                }
            }
        }
        for (JLanguageTool grammarTool : grammarTools) {
            for (RuleMatch match : grammarTool.check(text)) {
                Span span = new Span(match.getFromPos(), match.getToPos());
                issuesBySpan.computeIfAbsent(span, ignored -> new LinkedHashSet<>())
                    .addAll(match.getSuggestedReplacements());
            }
        }
        return issuesBySpan.entrySet().stream()
            .map(entry -> new Issue(
                entry.getKey().start(),
                entry.getKey().end(),
                entry.getValue().stream().limit(8).toList()
            ))
            .sorted((left, right) -> Integer.compare(left.start(), right.start()))
            .toList();
    }

    private void ensureInitialized() {
        if (spellingTools != null && grammarTools != null) return;
        List<JLanguageTool> initializedSpelling = new ArrayList<>(spellingLanguageCodes.size());
        for (String languageCode : spellingLanguageCodes) {
            JLanguageTool tool = spellingOnly(new JLanguageTool(
                Languages.getLanguageForShortCode(languageCode)
            ));
            if (tool != null) initializedSpelling.add(tool);
        }
        List<JLanguageTool> initializedGrammar = new ArrayList<>(grammarLanguageCodes.size());
        for (String languageCode : grammarLanguageCodes) {
            initializedGrammar.add(grammarOnly(new JLanguageTool(
                Languages.getLanguageForShortCode(languageCode)
            )));
        }
        spellingTools = List.copyOf(initializedSpelling);
        grammarTools = List.copyOf(initializedGrammar);
    }

    private static JLanguageTool spellingOnly(JLanguageTool tool) {
        boolean hasSpellingRule = false;
        for (Rule rule : tool.getAllRules()) {
            if (rule instanceof SpellingCheckRule) {
                hasSpellingRule = true;
            } else {
                tool.disableRule(rule.getId());
            }
        }
        return hasSpellingRule ? tool : null;
    }

    private static JLanguageTool grammarOnly(JLanguageTool tool) {
        for (Rule rule : tool.getAllRules()) {
            if (rule instanceof SpellingCheckRule) tool.disableRule(rule.getId());
        }
        return tool;
    }

    private static Map<Span, RuleMatch> spellingMatches(JLanguageTool tool, String text) throws IOException {
        Map<Span, RuleMatch> matches = new LinkedHashMap<>();
        for (RuleMatch match : tool.check(text)) {
            if (match.getRule() instanceof SpellingCheckRule) {
                matches.put(new Span(match.getFromPos(), match.getToPos()), match);
            }
        }
        return matches;
    }

    private static List<String> normalizedCodes(List<String> languageCodes) {
        return languageCodes == null
            ? List.of()
            : languageCodes.stream()
                .map(String::strip)
                .filter(code -> !code.isEmpty())
                .distinct()
                .toList();
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }

    record Issue(int start, int end, List<String> suggestions) {}
    private record Span(int start, int end) {}
}
