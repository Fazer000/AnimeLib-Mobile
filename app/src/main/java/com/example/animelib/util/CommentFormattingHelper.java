package com.example.animelib.util;

import android.graphics.Typeface;
import android.text.Editable;
import android.text.Spannable;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.style.CharacterStyle;
import android.text.style.StrikethroughSpan;
import android.text.style.StyleSpan;
import android.text.style.UnderlineSpan;
import android.text.style.UpdateAppearance;
import android.widget.EditText;

import java.util.ArrayList;
import java.util.List;

public class CommentFormattingHelper {

    public enum FormatType {
        BOLD, ITALIC, UNDERLINE, STRIKE, SPOILER, QUOTE
    }

    public static class CustomUnderlineSpan extends UnderlineSpan {}

    public static class SpoilerSpan extends CharacterStyle implements UpdateAppearance {
        @Override
        public void updateDrawState(TextPaint tp) {
            tp.bgColor = 0x40FF9B40;
            tp.setColor(0xFFFF9B40);
        }
    }

    public static class CustomQuoteSpan extends CharacterStyle implements UpdateAppearance {
        @Override
        public void updateDrawState(TextPaint tp) {
            tp.bgColor = 0x20888888;
            tp.setTextSkewX(-0.25f);
        }
    }

    /**
     * Применить форматирование без добавления текстовых тегов в EditText
     */
    public static void applyFormat(EditText editText, FormatType formatType) {
        if (editText == null) return;

        Editable editable = editText.getText();
        if (editable == null) return;

        int start = Math.max(editText.getSelectionStart(), 0);
        int end = Math.max(editText.getSelectionEnd(), 0);
        int selMin = Math.min(start, end);
        int selMax = Math.max(start, end);

        boolean hasSelection = selMin < selMax;

        if (!hasSelection) {
            String defaultText = "";
            switch (formatType) {
                case BOLD: defaultText = "жирный текст"; break;
                case ITALIC: defaultText = "курсивный текст"; break;
                case UNDERLINE: defaultText = "подчеркнутый текст"; break;
                case STRIKE: defaultText = "зачеркнутый текст"; break;
                case SPOILER: defaultText = "текст спойлера"; break;
                case QUOTE: defaultText = "текст цитаты"; break;
            }
            editable.insert(selMin, defaultText);
            selMax = selMin + defaultText.length();
        }

        switch (formatType) {
            case BOLD:
                toggleStyleSpan(editable, selMin, selMax, Typeface.BOLD);
                break;
            case ITALIC:
                toggleStyleSpan(editable, selMin, selMax, Typeface.ITALIC);
                break;
            case UNDERLINE:
                toggleUnderlineSpan(editable, selMin, selMax);
                break;
            case STRIKE:
                toggleStrikethroughSpan(editable, selMin, selMax);
                break;
            case SPOILER:
                toggleSpoilerSpan(editable, selMin, selMax);
                break;
            case QUOTE:
                toggleQuoteSpan(editable, selMin, selMax);
                break;
        }

        try {
            editText.setSelection(selMin, selMax);
        } catch (Exception ignored) {}
    }

    private static void toggleStyleSpan(Editable editable, int start, int end, int style) {
        StyleSpan[] spans = editable.getSpans(start, end, StyleSpan.class);
        boolean found = false;
        for (StyleSpan span : spans) {
            if (span.getStyle() == style) {
                int sStart = editable.getSpanStart(span);
                int sEnd = editable.getSpanEnd(span);
                if (sStart <= start && sEnd >= end) {
                    editable.removeSpan(span);
                    found = true;
                    break;
                }
            }
        }
        if (!found) {
            editable.setSpan(new StyleSpan(style), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

    private static void toggleUnderlineSpan(Editable editable, int start, int end) {
        CustomUnderlineSpan[] spans = editable.getSpans(start, end, CustomUnderlineSpan.class);
        if (spans != null && spans.length > 0) {
            for (CustomUnderlineSpan span : spans) {
                editable.removeSpan(span);
            }
        } else {
            editable.setSpan(new CustomUnderlineSpan(), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

    private static void toggleStrikethroughSpan(Editable editable, int start, int end) {
        StrikethroughSpan[] spans = editable.getSpans(start, end, StrikethroughSpan.class);
        if (spans != null && spans.length > 0) {
            for (StrikethroughSpan span : spans) {
                editable.removeSpan(span);
            }
        } else {
            editable.setSpan(new StrikethroughSpan(), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

    private static void toggleSpoilerSpan(Editable editable, int start, int end) {
        SpoilerSpan[] spans = editable.getSpans(start, end, SpoilerSpan.class);
        if (spans != null && spans.length > 0) {
            for (SpoilerSpan span : spans) {
                editable.removeSpan(span);
            }
        } else {
            editable.setSpan(new SpoilerSpan(), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

    private static void toggleQuoteSpan(Editable editable, int start, int end) {
        CustomQuoteSpan[] spans = editable.getSpans(start, end, CustomQuoteSpan.class);
        if (spans != null && spans.length > 0) {
            for (CustomQuoteSpan span : spans) {
                editable.removeSpan(span);
            }
        } else {
            editable.setSpan(new CustomQuoteSpan(), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

    public static void attachFormattingTextWatcher(EditText editText) {
        // Не требует принудительной модификации текста
    }

    private static class TagMarker {
        final String tag;
        final int priority;

        TagMarker(String tag, int priority) {
            this.tag = tag;
            this.priority = priority;
        }
    }

    /**
     * Конвертирует Spanned текст из EditText в строку с тегами BBCode перед отправкой на сервер
     */
    public static String toBBCode(CharSequence text) {
        if (text == null) return "";
        if (!(text instanceof Spanned)) return text.toString();

        Spanned spanned = (Spanned) text;
        int len = spanned.length();
        if (len == 0) return "";

        List<TagMarker>[] openTags = new List[len + 1];
        List<TagMarker>[] closeTags = new List[len + 1];

        for (int i = 0; i <= len; i++) {
            openTags[i] = new ArrayList<>();
            closeTags[i] = new ArrayList<>();
        }

        StyleSpan[] styleSpans = spanned.getSpans(0, len, StyleSpan.class);
        for (StyleSpan span : styleSpans) {
            int start = spanned.getSpanStart(span);
            int end = spanned.getSpanEnd(span);
            int flags = spanned.getSpanFlags(span);
            if ((flags & Spannable.SPAN_COMPOSING) == 0 && start >= 0 && end > start && end <= len) {
                if ((span.getStyle() & Typeface.BOLD) != 0) {
                    openTags[start].add(new TagMarker("[b]", 1));
                    closeTags[end].add(new TagMarker("[/b]", 1));
                }
                if ((span.getStyle() & Typeface.ITALIC) != 0) {
                    openTags[start].add(new TagMarker("[i]", 2));
                    closeTags[end].add(new TagMarker("[/i]", 2));
                }
            }
        }

        CustomUnderlineSpan[] uSpans = spanned.getSpans(0, len, CustomUnderlineSpan.class);
        for (CustomUnderlineSpan span : uSpans) {
            int start = spanned.getSpanStart(span);
            int end = spanned.getSpanEnd(span);
            int flags = spanned.getSpanFlags(span);
            if ((flags & Spannable.SPAN_COMPOSING) == 0 && start >= 0 && end > start && end <= len) {
                openTags[start].add(new TagMarker("[u]", 3));
                closeTags[end].add(new TagMarker("[/u]", 3));
            }
        }

        StrikethroughSpan[] sSpans = spanned.getSpans(0, len, StrikethroughSpan.class);
        for (StrikethroughSpan span : sSpans) {
            int start = spanned.getSpanStart(span);
            int end = spanned.getSpanEnd(span);
            int flags = spanned.getSpanFlags(span);
            if ((flags & Spannable.SPAN_COMPOSING) == 0 && start >= 0 && end > start && end <= len) {
                openTags[start].add(new TagMarker("[s]", 4));
                closeTags[end].add(new TagMarker("[/s]", 4));
            }
        }

        SpoilerSpan[] spoilerSpans = spanned.getSpans(0, len, SpoilerSpan.class);
        for (SpoilerSpan span : spoilerSpans) {
            int start = spanned.getSpanStart(span);
            int end = spanned.getSpanEnd(span);
            int flags = spanned.getSpanFlags(span);
            if ((flags & Spannable.SPAN_COMPOSING) == 0 && start >= 0 && end > start && end <= len) {
                openTags[start].add(new TagMarker("[spoiler]", 5));
                closeTags[end].add(new TagMarker("[/spoiler]", 5));
            }
        }

        CustomQuoteSpan[] quoteSpans = spanned.getSpans(0, len, CustomQuoteSpan.class);
        for (CustomQuoteSpan span : quoteSpans) {
            int start = spanned.getSpanStart(span);
            int end = spanned.getSpanEnd(span);
            int flags = spanned.getSpanFlags(span);
            if ((flags & Spannable.SPAN_COMPOSING) == 0 && start >= 0 && end > start && end <= len) {
                openTags[start].add(new TagMarker("[quote]", 6));
                closeTags[end].add(new TagMarker("[/quote]", 6));
            }
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len; i++) {
            for (TagMarker marker : closeTags[i]) {
                sb.append(marker.tag);
            }
            for (TagMarker marker : openTags[i]) {
                sb.append(marker.tag);
            }
            sb.append(spanned.charAt(i));
        }
        for (TagMarker marker : closeTags[len]) {
            sb.append(marker.tag);
        }
        for (TagMarker marker : openTags[len]) {
            sb.append(marker.tag);
        }

        return sb.toString();
    }
}
