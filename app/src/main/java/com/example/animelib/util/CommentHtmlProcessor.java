package com.example.animelib.util;

import android.content.Context;
import android.graphics.Typeface;
import android.text.Html;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StrikethroughSpan;
import android.text.style.StyleSpan;
import android.text.style.UnderlineSpan;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.example.animelib.R;
import com.example.animelib.ui.SpoilerView;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.Elements;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Утилита для обработки HTML комментариев с поддержкой фонового парсинга,
 * спойлеров, форматирования и цитат без нагрузки на главный UI-поток.
 */
public class CommentHtmlProcessor {

    private static final String TAG = "CommentHtmlProcessor";

    private static final Pattern PATTERN_MULTI_NEWLINES = Pattern.compile("\\n\\s*\\n+");
    private static final Pattern PATTERN_WS_BEFORE_NEWLINE = Pattern.compile("[ \\t\\x0B\\f\\r]+\\n");
    private static final Pattern PATTERN_WS_AFTER_NEWLINE = Pattern.compile("\\n[ \\t\\x0B\\f\\r]+");
    private static final Pattern PATTERN_MULTI_SPACES = Pattern.compile("[ \\t\\x0B\\f\\r]{2,}");

    public enum BlockType {
        PARAGRAPH,
        QUOTE,
        CODE,
        HEADING,
        LIST_ITEM,
        SPOILER
    }

    public static final class ParsedBlock {
        public final BlockType type;
        public final CharSequence text;
        public final String spoilerTitle;
        public final float headingSizeSp;

        private ParsedBlock(BlockType type, CharSequence text, String spoilerTitle, float headingSizeSp) {
            this.type = type;
            this.text = text != null ? text : "";
            this.spoilerTitle = spoilerTitle;
            this.headingSizeSp = headingSizeSp;
        }

        public static ParsedBlock paragraph(CharSequence text) {
            return new ParsedBlock(BlockType.PARAGRAPH, text, null, 13f);
        }

        public static ParsedBlock quote(CharSequence text) {
            return new ParsedBlock(BlockType.QUOTE, text, null, 13f);
        }

        public static ParsedBlock code(CharSequence text) {
            return new ParsedBlock(BlockType.CODE, text, null, 12f);
        }

        public static ParsedBlock heading(CharSequence text, float sizeSp) {
            return new ParsedBlock(BlockType.HEADING, text, null, sizeSp);
        }

        public static ParsedBlock listItem(CharSequence text) {
            return new ParsedBlock(BlockType.LIST_ITEM, text, null, 13f);
        }

        public static ParsedBlock spoiler(String title, CharSequence content) {
            return new ParsedBlock(BlockType.SPOILER, content, title, 13f);
        }
    }

    /**
     * Неизменяемый результат фонового парсинга HTML комментария.
     * Если {@code blocks.isEmpty()}, комментарий отображается через один лёгкий {@code TextView}.
     */
    public static final class ParsedCommentContent {
        public final String sourceHashKey;
        public final CharSequence simpleText;
        public final List<ParsedBlock> blocks;

        private ParsedCommentContent(String sourceHashKey, CharSequence simpleText, List<ParsedBlock> blocks) {
            this.sourceHashKey = sourceHashKey;
            this.simpleText = simpleText != null ? simpleText : "";
            this.blocks = blocks != null ? blocks : Collections.emptyList();
        }

        public static ParsedCommentContent simple(String sourceHashKey, CharSequence text) {
            return new ParsedCommentContent(sourceHashKey, text, Collections.emptyList());
        }

        public static ParsedCommentContent complex(String sourceHashKey, List<ParsedBlock> blocks) {
            return new ParsedCommentContent(sourceHashKey, null, blocks);
        }

        public boolean isSimple() {
            return blocks.isEmpty();
        }
    }

    private final Context context;
    private final int primaryTextColor;
    private final int purpleColor;
    private final float density;

    public CommentHtmlProcessor(Context context) {
        this.context = context;
        this.density = context.getResources().getDisplayMetrics().density;
        this.primaryTextColor = resolvePrimaryTextColor(context);
        this.purpleColor = ContextCompat.getColor(context, R.color.purple_primary);
    }

    public static int resolvePrimaryTextColor(Context context) {
        if (context == null) return 0xFFFFFFFF;
        TypedValue typedValue = new TypedValue();
        if (context.getTheme() != null && context.getTheme().resolveAttribute(R.attr.primaryTextColor, typedValue, true)) {
            return typedValue.resourceId != 0
                    ? ContextCompat.getColor(context, typedValue.resourceId)
                    : typedValue.data;
        }
        return ContextCompat.getColor(context, R.color.primary_text_color);
    }

    public static String computeSourceKey(String html) {
        if (html == null) return "0:0";
        return html.length() + ":" + html.hashCode();
    }

    /**
     * Быстрая проверка наличия блочных элементов, требующих составного контейнера (спойлеры, цитаты, код, списки).
     * Инлайн-теги (strong, em, u, strike, p, br, a) рендерятся в один TextView без создания дочерних View!
     */
    public static boolean containsBlockStructure(String html) {
        if (html == null || html.isEmpty()) return false;
        String lower = html.toLowerCase(Locale.ROOT);
        return lower.contains("spoiler-node")
                || lower.contains("<blockquote")
                || lower.contains("<pre")
                || lower.contains("<code")
                || lower.contains("<ul")
                || lower.contains("<ol")
                || lower.contains("<h1")
                || lower.contains("<h2")
                || lower.contains("<h3")
                || lower.contains("<h4")
                || lower.contains("<h5")
                || lower.contains("<h6");
    }

    public static String normalizeHtmlWhitespace(String rawHtml) {
        if (rawHtml == null || rawHtml.isEmpty()) return "";
        String s = rawHtml.replace("\\\"", "\"").trim();
        if (s.isEmpty()) return "";
        s = PATTERN_MULTI_NEWLINES.matcher(s).replaceAll("\n");
        s = PATTERN_WS_BEFORE_NEWLINE.matcher(s).replaceAll("\n");
        s = PATTERN_WS_AFTER_NEWLINE.matcher(s).replaceAll("\n");
        s = PATTERN_MULTI_SPACES.matcher(s).replaceAll(" ");
        return s.trim();
    }

    /**
     * Потокобезопасный парсинг HTML комментария для фонового потока.
     * Не создаёт Android View и полностью избавляет главный поток от Jsoup/Regex/Html.fromHtml.
     */
    public static ParsedCommentContent parseHtmlOffThread(String htmlContent, int primaryTextColor, int purpleColor) {
        String key = computeSourceKey(htmlContent);
        if (htmlContent == null || htmlContent.trim().isEmpty()) {
            return ParsedCommentContent.simple(key, "");
        }

        String cleanedHtml = normalizeHtmlWhitespace(htmlContent);
        if (cleanedHtml.isEmpty()) {
            return ParsedCommentContent.simple(key, "");
        }

        if (!containsBlockStructure(cleanedHtml)) {
            try {
                Spanned rawSpanned = Html.fromHtml(cleanedHtml, Html.FROM_HTML_MODE_LEGACY);
                return ParsedCommentContent.simple(key, trimOuterWhitespace(rawSpanned));
            } catch (Exception e) {
                return ParsedCommentContent.simple(key, cleanedHtml);
            }
        }

        try {
            Document doc = Jsoup.parse(cleanedHtml);
            List<ParsedBlock> blocks = new ArrayList<>();
            for (Element element : doc.body().children()) {
                collectBlocks(element, blocks, primaryTextColor, purpleColor);
            }

            if (blocks.isEmpty()) {
                Spanned fallback = Html.fromHtml(cleanedHtml, Html.FROM_HTML_MODE_LEGACY);
                return ParsedCommentContent.simple(key, trimOuterWhitespace(fallback));
            }

            boolean allParagraphs = true;
            for (ParsedBlock b : blocks) {
                if (b.type != BlockType.PARAGRAPH) {
                    allParagraphs = false;
                    break;
                }
            }
            if (allParagraphs) {
                SpannableStringBuilder joined = new SpannableStringBuilder();
                for (ParsedBlock b : blocks) {
                    if (b.text.length() == 0) continue;
                    if (joined.length() > 0) {
                        joined.append("\n");
                    }
                    joined.append(b.text);
                }
                return ParsedCommentContent.simple(key, trimOuterWhitespace(joined));
            }

            return ParsedCommentContent.complex(key, blocks);
        } catch (Exception e) {
            Log.e(TAG, "Error parsing HTML in background", e);
            try {
                Spanned spanned = Html.fromHtml(cleanedHtml, Html.FROM_HTML_MODE_LEGACY);
                return ParsedCommentContent.simple(key, trimOuterWhitespace(spanned));
            } catch (Exception ignored) {
                return ParsedCommentContent.simple(key, cleanedHtml);
            }
        }
    }

    private static void collectBlocks(Element element, List<ParsedBlock> out, int primaryTextColor, int purpleColor) {
        String tagName = element.tagName().toLowerCase(Locale.ROOT);
        switch (tagName) {
            case "p": {
                Elements spoilerElements = element.select(".spoiler-node");
                if (!spoilerElements.isEmpty()) {
                    for (Node child : element.childNodes()) {
                        if (child instanceof TextNode) {
                            String txt = ((TextNode) child).text().trim();
                            if (!txt.isEmpty()) {
                                out.add(ParsedBlock.paragraph(txt));
                            }
                        } else if (child instanceof Element) {
                            Element ce = (Element) child;
                            if (ce.hasClass("spoiler-node")) {
                                collectSpoilerBlock(ce, out, purpleColor);
                            } else if (!ce.select(".spoiler-node").isEmpty()) {
                                collectBlocks(ce, out, primaryTextColor, purpleColor);
                            } else {
                                CharSequence inline = trimOuterWhitespace(processInlineElementsStatic(ce, purpleColor));
                                if (inline.length() > 0) {
                                    out.add(ParsedBlock.paragraph(inline));
                                }
                            }
                        }
                    }
                    return;
                }
                if (element.text().trim().isEmpty()) return;
                CharSequence spanned = trimOuterWhitespace(processInlineElementsStatic(element, purpleColor));
                if (spanned.length() > 0) {
                    out.add(ParsedBlock.paragraph(spanned));
                }
                break;
            }
            case "blockquote": {
                SpannableStringBuilder builder = new SpannableStringBuilder();
                builder.append(processInlineElementsStatic(element, purpleColor));
                CharSequence trimmed = trimOuterWhitespace(builder);
                if (trimmed.length() > 0) {
                    SpannableStringBuilder styled = new SpannableStringBuilder(trimmed);
                    styled.setSpan(new StyleSpan(Typeface.ITALIC), 0, styled.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                    styled.setSpan(new ForegroundColorSpan(primaryTextColor), 0, styled.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                    out.add(ParsedBlock.quote(styled));
                }
                break;
            }
            case "pre":
            case "code": {
                CharSequence codeText = trimTrailingNewlines(element.text());
                if (codeText.length() > 0) {
                    out.add(ParsedBlock.code(codeText));
                }
                break;
            }
            case "h1":
            case "h2":
            case "h3":
            case "h4":
            case "h5":
            case "h6": {
                float sizeSp = 15f;
                if ("h1".equals(tagName)) sizeSp = 17f;
                else if ("h2".equals(tagName)) sizeSp = 16f;
                CharSequence content = trimOuterWhitespace(processInlineElementsStatic(element, purpleColor));
                if (content.length() > 0) {
                    out.add(ParsedBlock.heading(content, sizeSp));
                }
                break;
            }
            case "ul":
            case "ol": {
                boolean isOrdered = "ol".equals(tagName);
                int index = 1;
                for (Element li : element.children()) {
                    String prefix = isOrdered ? (index++) + ". " : "• ";
                    SpannableStringBuilder builder = new SpannableStringBuilder(prefix);
                    builder.setSpan(new ForegroundColorSpan(purpleColor), 0, prefix.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                    builder.append(trimOuterWhitespace(processInlineElementsStatic(li, purpleColor)));
                    out.add(ParsedBlock.listItem(trimTrailingNewlines(builder)));
                }
                break;
            }
            case "div":
            case "span": {
                if (element.hasClass("spoiler-node")) {
                    collectSpoilerBlock(element, out, purpleColor);
                } else if (!element.select(".spoiler-node").isEmpty()) {
                    for (Element child : element.children()) {
                        collectBlocks(child, out, primaryTextColor, purpleColor);
                    }
                } else {
                    CharSequence spanned = trimOuterWhitespace(processInlineElementsStatic(element, purpleColor));
                    if (spanned.length() > 0) {
                        out.add(ParsedBlock.paragraph(spanned));
                    }
                }
                break;
            }
            default: {
                CharSequence spanned = trimOuterWhitespace(processInlineElementsStatic(element, purpleColor));
                if (spanned.length() > 0) {
                    out.add(ParsedBlock.paragraph(spanned));
                }
                break;
            }
        }
    }

    private static void collectSpoilerBlock(Element element, List<ParsedBlock> out, int purpleColor) {
        String spoilerText = element.attr("data-spoiler-text");
        Elements spoilerTextElements = element.select(".spoiler-node__text");
        SpannableStringBuilder contentBuilder = new SpannableStringBuilder();
        if (!spoilerTextElements.isEmpty()) {
            for (Element textElement : spoilerTextElements) {
                CharSequence part = trimOuterWhitespace(processInlineElementsStatic(textElement, purpleColor));
                if (part.length() > 0) {
                    if (contentBuilder.length() > 0) {
                        contentBuilder.append("\n");
                    }
                    contentBuilder.append(part);
                }
            }
        } else {
            contentBuilder.append(trimOuterWhitespace(processInlineElementsStatic(element, purpleColor)));
        }
        String title = (spoilerText != null && !spoilerText.trim().isEmpty()) ? spoilerText.trim() : "Спойлер";
        out.add(ParsedBlock.spoiler(title, trimTrailingNewlines(contentBuilder)));
    }

    private static Spanned processInlineElementsStatic(Element element, int purpleColor) {
        SpannableStringBuilder builder = new SpannableStringBuilder();

        for (Node node : element.childNodes()) {
            if (node instanceof TextNode) {
                String text = ((TextNode) node).text();
                if (!text.isEmpty()) {
                    builder.append(text);
                }
            } else if (node instanceof Element) {
                Element childElement = (Element) node;
                String tagName = childElement.tagName().toLowerCase(Locale.ROOT);
                int start = builder.length();

                switch (tagName) {
                    case "strong":
                    case "b": {
                        CharSequence nested = processInlineElementsStatic(childElement, purpleColor);
                        builder.append(nested);
                        if (builder.length() > start) {
                            builder.setSpan(new StyleSpan(Typeface.BOLD),
                                    start, builder.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                        }
                        break;
                    }
                    case "em":
                    case "i": {
                        CharSequence nested = processInlineElementsStatic(childElement, purpleColor);
                        builder.append(nested);
                        if (builder.length() > start) {
                            builder.setSpan(new StyleSpan(Typeface.ITALIC),
                                    start, builder.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                        }
                        break;
                    }
                    case "u": {
                        CharSequence nested = processInlineElementsStatic(childElement, purpleColor);
                        builder.append(nested);
                        if (builder.length() > start) {
                            builder.setSpan(new UnderlineSpan(),
                                    start, builder.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                        }
                        break;
                    }
                    case "strike":
                    case "s": {
                        CharSequence nested = processInlineElementsStatic(childElement, purpleColor);
                        builder.append(nested);
                        if (builder.length() > start) {
                            builder.setSpan(new StrikethroughSpan(),
                                    start, builder.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                        }
                        break;
                    }
                    case "code": {
                        String codeText = childElement.text().trim();
                        builder.append(codeText);
                        if (builder.length() > start) {
                            builder.setSpan(new ForegroundColorSpan(purpleColor),
                                    start, builder.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                        }
                        break;
                    }
                    case "a": {
                        String linkText = childElement.text().trim();
                        if (linkText.isEmpty()) linkText = childElement.attr("href");
                        builder.append(linkText);
                        if (builder.length() > start) {
                            builder.setSpan(new ForegroundColorSpan(purpleColor),
                                    start, builder.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                            builder.setSpan(new UnderlineSpan(),
                                    start, builder.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                        }
                        break;
                    }
                    case "br":
                        builder.append("\n");
                        break;
                    default: {
                        Spanned nested = processInlineElementsStatic(childElement, purpleColor);
                        builder.append(nested);
                        break;
                    }
                }
            }
        }
        return builder;
    }

    /**
     * Отрисовать заранее распарсенные блоки в контейнер на UI-потоке (без Jsoup и Regex).
     */
    public void renderParsedBlocks(ParsedCommentContent parsed, LinearLayout container) {
        if (container == null) return;
        container.removeAllViews();
        if (parsed == null) return;

        if (parsed.isSimple()) {
            if (parsed.simpleText.length() > 0) {
                TextView tv = createTextView();
                tv.setText(parsed.simpleText);
                container.addView(tv);
            }
            return;
        }

        for (ParsedBlock block : parsed.blocks) {
            switch (block.type) {
                case PARAGRAPH: {
                    TextView textView = createTextView();
                    textView.setText(block.text);
                    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                    );
                    params.setMargins(0, dpToPx(2), 0, dpToPx(2));
                    textView.setLayoutParams(params);
                    container.addView(textView);
                    break;
                }
                case QUOTE: {
                    LinearLayout quoteContainer = new LinearLayout(context);
                    quoteContainer.setOrientation(LinearLayout.HORIZONTAL);
                    quoteContainer.setGravity(Gravity.TOP);
                    quoteContainer.setPadding(dpToPx(12), dpToPx(8), dpToPx(12), dpToPx(8));
                    quoteContainer.setBackgroundResource(R.drawable.quote_background);

                    ImageView quoteIcon = new ImageView(context);
                    quoteIcon.setImageResource(R.drawable.ic_quote_mark);
                    LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dpToPx(16), dpToPx(16));
                    iconParams.setMarginEnd(dpToPx(8));
                    iconParams.topMargin = dpToPx(2);
                    quoteIcon.setLayoutParams(iconParams);
                    quoteContainer.addView(quoteIcon);

                    TextView textView = createTextView();
                    textView.setText(block.text);
                    LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(
                            0,
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            1.0f
                    );
                    textView.setLayoutParams(textParams);
                    quoteContainer.addView(textView);

                    LinearLayout.LayoutParams containerParams = new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                    );
                    containerParams.setMargins(0, dpToPx(6), 0, dpToPx(6));
                    quoteContainer.setLayoutParams(containerParams);
                    container.addView(quoteContainer);
                    break;
                }
                case CODE: {
                    TextView textView = createTextView();
                    textView.setBackgroundResource(R.drawable.code_block_background);
                    textView.setTypeface(Typeface.MONOSPACE);
                    textView.setTextSize(12);
                    textView.setPadding(dpToPx(10), dpToPx(8), dpToPx(10), dpToPx(8));
                    textView.setText(block.text);
                    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                    );
                    params.setMargins(0, dpToPx(6), 0, dpToPx(6));
                    textView.setLayoutParams(params);
                    container.addView(textView);
                    break;
                }
                case HEADING: {
                    TextView textView = createTextView();
                    textView.setTextSize(block.headingSizeSp);
                    textView.setTypeface(null, Typeface.BOLD);
                    textView.setText(block.text);
                    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                    );
                    params.setMargins(0, dpToPx(6), 0, dpToPx(4));
                    textView.setLayoutParams(params);
                    container.addView(textView);
                    break;
                }
                case LIST_ITEM: {
                    TextView textView = createTextView();
                    textView.setText(block.text);
                    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                    );
                    params.setMargins(dpToPx(12), dpToPx(2), 0, dpToPx(2));
                    textView.setLayoutParams(params);
                    container.addView(textView);
                    break;
                }
                case SPOILER: {
                    SpoilerView spoilerView = new SpoilerView(context);
                    spoilerView.setSpoilerData(block.spoilerTitle, block.text);
                    container.addView(spoilerView);
                    break;
                }
            }
        }
    }

    /**
     * Обратная совместимость: обработать HTML комментарий и создать View элементы.
     */
    public void processCommentHtml(String htmlContent, LinearLayout container) {
        if (htmlContent == null || htmlContent.trim().isEmpty()) {
            if (container != null) container.removeAllViews();
            return;
        }
        ParsedCommentContent parsed = parseHtmlOffThread(htmlContent, primaryTextColor, purpleColor);
        renderParsedBlocks(parsed, container);
    }

    private TextView createTextView() {
        TextView textView = new TextView(context);
        textView.setTextColor(primaryTextColor);
        textView.setTextSize(13);
        textView.setLineSpacing(dpToPx(2), 1.0f);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        params.setMargins(0, 0, 0, 0);
        textView.setLayoutParams(params);
        return textView;
    }

    public static CharSequence trimOuterWhitespace(CharSequence text) {
        if (text == null) return "";
        int start = 0;
        int end = text.length();
        while (start < end && Character.isWhitespace(text.charAt(start))) {
            start++;
        }
        while (end > start && Character.isWhitespace(text.charAt(end - 1))) {
            end--;
        }
        return (start == 0 && end == text.length()) ? text : text.subSequence(start, end);
    }

    private static CharSequence trimTrailingNewlines(CharSequence text) {
        if (text == null) return "";
        int len = text.length();
        while (len > 0 && (text.charAt(len - 1) == '\n' || text.charAt(len - 1) == '\r' || Character.isWhitespace(text.charAt(len - 1)))) {
            len--;
        }
        return text.subSequence(0, len);
    }

    private int dpToPx(int dp) {
        return (int) (dp * density);
    }
}
