package com.example.animelib.util;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Утилита для парсинга текстовых списков экспорта Anixart.
 * Формат строки:
 * 1 / "Русское название" / "Второе/английское название"
 * Либо без кавычек, с различными пробелами вокруг слэшей.
 */
public class AnixartParser {

    private static final Pattern QUOTED_PATTERN = Pattern.compile("^\\s*\\d+\\s*/\\s*\"([^\"]*)\"\\s*/\\s*\"([^\"]*)\"");
    private static final Pattern UNQUOTED_SLASH_PATTERN = Pattern.compile("^\\s*\\d+\\s*/\\s*([^/]+?)\\s*/\\s*(.+?)\\s*$");

    public static class AnixartEntry {
        private final int index;
        private final String primaryTitle;
        private final String searchTitle; // Второе название (английское/ромадзи)
        private final String rawLine;

        public AnixartEntry(int index, String primaryTitle, String searchTitle, String rawLine) {
            this.index = index;
            this.primaryTitle = primaryTitle != null ? primaryTitle.trim() : "";
            this.searchTitle = searchTitle != null ? searchTitle.trim() : "";
            this.rawLine = rawLine;
        }

        public int getIndex() {
            return index;
        }

        public String getPrimaryTitle() {
            return primaryTitle;
        }

        public String getSearchTitle() {
            return searchTitle;
        }

        public String getEffectiveTitle() {
            if (!searchTitle.isEmpty()) {
                return searchTitle;
            }
            return primaryTitle;
        }

        public String getRawLine() {
            return rawLine;
        }
    }

    /**
     * Парсит весь текст файла и возвращает список валидных записей
     */
    public static List<AnixartEntry> parse(String text) {
        List<AnixartEntry> result = new ArrayList<>();
        if (text == null || text.trim().isEmpty()) {
            return result;
        }

        String[] lines = text.split("\\r?\\n");
        int autoIndex = 1;
        for (String line : lines) {
            if (line == null) continue;
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("//")) {
                continue;
            }

            AnixartEntry entry = parseLine(trimmed, autoIndex);
            if (entry != null && (!entry.getSearchTitle().isEmpty() || !entry.getPrimaryTitle().isEmpty())) {
                result.add(entry);
                autoIndex++;
            }
        }
        return result;
    }

    /**
     * Парсит отдельную строку
     */
    public static AnixartEntry parseLine(String line, int defaultIndex) {
        if (line == null || line.trim().isEmpty()) return null;

        // Попробуем парсить с кавычками: 1 / "Русское" / "Английское"
        Matcher quotedMatcher = QUOTED_PATTERN.matcher(line);
        if (quotedMatcher.find()) {
            String primary = quotedMatcher.group(1);
            String secondary = quotedMatcher.group(2);
            int idx = extractIndex(line, defaultIndex);
            return new AnixartEntry(idx, cleanTitle(primary), cleanTitle(secondary), line);
        }

        // Попробуем парсить через разделитель слэш: 1 / Русское / Английское
        Matcher slashMatcher = UNQUOTED_SLASH_PATTERN.matcher(line);
        if (slashMatcher.find()) {
            String primary = stripQuotes(slashMatcher.group(1));
            String secondary = stripQuotes(slashMatcher.group(2));
            int idx = extractIndex(line, defaultIndex);
            return new AnixartEntry(idx, cleanTitle(primary), cleanTitle(secondary), line);
        }

        // Fallback: разделение по " / "
        String[] parts = line.split("\\s*/\\s*");
        if (parts.length >= 3) {
            int idx = parseLeadingInt(parts[0], defaultIndex);
            String primary = stripQuotes(parts[1]);
            String secondary = stripQuotes(parts[2]);
            return new AnixartEntry(idx, cleanTitle(primary), cleanTitle(secondary), line);
        } else if (parts.length == 2) {
            // Например: "Русское" / "Английское" (без номера)
            String primary = stripQuotes(parts[0]);
            String secondary = stripQuotes(parts[1]);
            return new AnixartEntry(defaultIndex, cleanTitle(primary), cleanTitle(secondary), line);
        }

        return null;
    }

    private static int extractIndex(String line, int defaultIndex) {
        try {
            int slashIdx = line.indexOf('/');
            if (slashIdx > 0) {
                String numStr = line.substring(0, slashIdx).replaceAll("[^0-9]", "");
                if (!numStr.isEmpty()) {
                    return Integer.parseInt(numStr);
                }
            }
        } catch (Exception ignored) {}
        return defaultIndex;
    }

    private static int parseLeadingInt(String str, int defaultIndex) {
        try {
            String numStr = str.replaceAll("[^0-9]", "");
            if (!numStr.isEmpty()) {
                return Integer.parseInt(numStr);
            }
        } catch (Exception ignored) {}
        return defaultIndex;
    }

    private static String stripQuotes(String str) {
        if (str == null) return "";
        String s = str.trim();
        if (s.startsWith("\"") && s.endsWith("\"") && s.length() >= 2) {
            return s.substring(1, s.length() - 1).trim();
        }
        if (s.startsWith("«") && s.endsWith("»") && s.length() >= 2) {
            return s.substring(1, s.length() - 1).trim();
        }
        return s;
    }

    private static String cleanTitle(String title) {
        if (title == null) return "";
        return title.trim();
    }
}
