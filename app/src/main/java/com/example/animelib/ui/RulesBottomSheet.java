package com.example.animelib.ui;

import android.content.Context;
import android.os.Bundle;
import android.text.Html;
import android.text.method.LinkMovementMethod;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import com.example.animelib.R;
import com.example.animelib.api.ApiService;
import com.example.animelib.models.ArticleResponse;
import com.example.animelib.util.FlexibleBottomSheetDialog;
import com.example.animelib.util.FloatingBottomSheetUtils;
import com.google.android.material.button.MaterialButton;

import java.util.List;

public class RulesBottomSheet extends FlexibleBottomSheetDialog {

    private static final String TAG = "RulesBottomSheet";

    private ProgressBar pbRulesLoading;
    private LinearLayout layoutRulesError;
    private TextView tvRulesError;
    private MaterialButton btnRulesRetry;
    private LinearLayout rulesContentContainer;
    private TextView tvRulesTitle;
    private MaterialButton btnUnderstandRules;

    private ApiService apiService;

    public RulesBottomSheet(@NonNull Context context) {
        super(context);
        this.apiService = new ApiService(context);
    }

    public RulesBottomSheet(@NonNull Context context, ApiService apiService) {
        super(context);
        this.apiService = apiService != null ? apiService : new ApiService(context);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        View view = LayoutInflater.from(getContext()).inflate(R.layout.bs_comment_rules, null);
        setContentView(view);
        FloatingBottomSheetUtils.setupFloatingStyle(this);

        tvRulesTitle = view.findViewById(R.id.tvRulesTitle);
        ImageButton btnCloseRules = view.findViewById(R.id.btnCloseRules);
        pbRulesLoading = view.findViewById(R.id.pbRulesLoading);
        layoutRulesError = view.findViewById(R.id.layoutRulesError);
        tvRulesError = view.findViewById(R.id.tvRulesError);
        btnRulesRetry = view.findViewById(R.id.btnRulesRetry);
        rulesContentContainer = view.findViewById(R.id.rulesContentContainer);
        btnUnderstandRules = view.findViewById(R.id.btnUnderstandRules);

        if (btnCloseRules != null) {
            btnCloseRules.setOnClickListener(v -> dismiss());
        }
        if (btnUnderstandRules != null) {
            btnUnderstandRules.setOnClickListener(v -> dismiss());
        }
        if (btnRulesRetry != null) {
            btnRulesRetry.setOnClickListener(v -> loadRules());
        }

        loadRules();
    }

    private void loadRules() {
        if (pbRulesLoading != null) pbRulesLoading.setVisibility(View.VISIBLE);
        if (layoutRulesError != null) layoutRulesError.setVisibility(View.GONE);
        if (rulesContentContainer != null) {
            rulesContentContainer.removeAllViews();
            rulesContentContainer.setVisibility(View.GONE);
        }
        if (btnUnderstandRules != null) btnUnderstandRules.setVisibility(View.GONE);

        apiService.getRulesArticle(new ApiService.ArticleCallback() {
            @Override
            public void onArticleReceived(ArticleResponse response) {
                if (pbRulesLoading != null) pbRulesLoading.setVisibility(View.GONE);
                if (response != null && response.getPost() != null) {
                    displayRules(response.getPost());
                } else {
                    showError("Не удалось получить данные правил.");
                }
            }

            @Override
            public void onError(String error) {
                Log.e(TAG, "Failed to load rules: " + error);
                if (pbRulesLoading != null) pbRulesLoading.setVisibility(View.GONE);
                showError("Ошибка загрузки правил");
            }
        });
    }

    private void showError(String message) {
        if (layoutRulesError != null) layoutRulesError.setVisibility(View.VISIBLE);
        if (tvRulesError != null) {
            tvRulesError.setText(message != null ? message : "Ошибка загрузки правил");
        }
        if (rulesContentContainer != null) rulesContentContainer.setVisibility(View.GONE);
        if (btnUnderstandRules != null) btnUnderstandRules.setVisibility(View.GONE);
    }

    private void displayRules(ArticleResponse.Post post) {
        if (post == null) return;

        if (tvRulesTitle != null && post.getTitle() != null && !post.getTitle().isEmpty()) {
            tvRulesTitle.setText(post.getTitle());
        }

        if (rulesContentContainer == null) return;
        rulesContentContainer.removeAllViews();
        rulesContentContainer.setVisibility(View.VISIBLE);
        if (btnUnderstandRules != null) btnUnderstandRules.setVisibility(View.VISIBLE);

        ArticleResponse.DocContent docContent = post.getContent();
        if (docContent == null || docContent.getContent() == null || docContent.getContent().isEmpty()) {
            TextView tvEmpty = new TextView(getContext());
            tvEmpty.setText("Правила отсутствуют.");
            tvEmpty.setTextColor(ContextCompat.getColor(getContext(), R.color.secondary_text_color));
            rulesContentContainer.addView(tvEmpty);
            return;
        }

        List<ArticleResponse.Node> nodes = docContent.getContent();
        renderBlockNodes(nodes, rulesContentContainer, 0);
    }

    private void renderBlockNodes(List<ArticleResponse.Node> nodes, LinearLayout container, int depth) {
        if (nodes == null || container == null) return;
        Context ctx = getContext();
        float density = ctx.getResources().getDisplayMetrics().density;
        LayoutInflater inflater = LayoutInflater.from(ctx);
        String accentHex = getAccentColorHex(ctx);

        for (ArticleResponse.Node node : nodes) {
            if (node == null) continue;
            String type = node.getType() != null ? node.getType() : "";

            switch (type) {
                case "heading": {
                    String html = renderInlineChildrenToHtml(node.getContent(), accentHex).trim();
                    if (html.isEmpty()) break;
                    int level = node.getAttrs() != null ? node.getAttrs().getLevel() : 2;
                    addHeadingView(container, html, level, density);
                    break;
                }

                case "bulletList":
                case "orderedList": {
                    boolean ordered = "orderedList".equals(type);
                    int startIdx = (ordered && node.getAttrs() != null) ? node.getAttrs().getStart() : 1;
                    if (node.getContent() != null) {
                        LinearLayout listContainer = new LinearLayout(ctx);
                        listContainer.setOrientation(LinearLayout.VERTICAL);
                        LinearLayout.LayoutParams listLp = new LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT
                        );
                        listLp.topMargin = Math.round(2 * density);
                        listLp.bottomMargin = Math.round(8 * density);
                        if (depth > 0) {
                            listLp.leftMargin = Math.round(12 * density);
                        }
                        listContainer.setLayoutParams(listLp);

                        int itemIndex = startIdx;
                        for (ArticleResponse.Node listItem : node.getContent()) {
                            if (listItem == null) continue;
                            String marker = ordered ? (itemIndex + ".") : "•";
                            addListItemView(listContainer, listItem, marker, accentHex, density, depth);
                            itemIndex++;
                        }
                        if (listContainer.getChildCount() > 0) {
                            container.addView(listContainer);
                        }
                    }
                    break;
                }

                case "blockquote": {
                    LinearLayout quoteLayout = new LinearLayout(ctx);
                    quoteLayout.setOrientation(LinearLayout.HORIZONTAL);
                    LinearLayout.LayoutParams quoteLp = new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                    );
                    quoteLp.topMargin = Math.round(4 * density);
                    quoteLp.bottomMargin = Math.round(10 * density);
                    quoteLayout.setLayoutParams(quoteLp);

                    View bar = new View(ctx);
                    LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(
                            Math.round(3 * density),
                            LinearLayout.LayoutParams.MATCH_PARENT
                    );
                    barLp.rightMargin = Math.round(12 * density);
                    bar.setLayoutParams(barLp);
                    bar.setBackgroundColor(resolveAccentColor(ctx));

                    LinearLayout quoteContent = new LinearLayout(ctx);
                    quoteContent.setOrientation(LinearLayout.VERTICAL);
                    quoteContent.setLayoutParams(new LinearLayout.LayoutParams(
                            0,
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            1f
                    ));

                    renderBlockNodes(node.getContent(), quoteContent, depth + 1);
                    if (quoteContent.getChildCount() > 0) {
                        quoteLayout.addView(bar);
                        quoteLayout.addView(quoteContent);
                        container.addView(quoteLayout);
                    }
                    break;
                }

                case "horizontalRule": {
                    View divider = new View(ctx);
                    LinearLayout.LayoutParams divLp = new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            Math.max(1, Math.round(0.8f * density))
                    );
                    divLp.topMargin = Math.round(8 * density);
                    divLp.bottomMargin = Math.round(12 * density);
                    divider.setLayoutParams(divLp);
                    divider.setBackgroundColor(ContextCompat.getColor(ctx, R.color.bs_divider_color));
                    container.addView(divider);
                    break;
                }

                case "paragraph":
                default: {
                    String html = renderNodeToHtml(node, accentHex).trim();
                    if (html.isEmpty()) break;

                    // Check if paragraph starts with markdown heading syntax (#, ##, ###)
                    if (html.startsWith("#")) {
                        int hashCount = 0;
                        while (hashCount < html.length() && html.charAt(hashCount) == '#') {
                            hashCount++;
                        }
                        if (hashCount <= 4 && hashCount < html.length() && html.charAt(hashCount) == ' ') {
                            String headingHtml = html.substring(hashCount + 1).trim();
                            addHeadingView(container, headingHtml, hashCount, density);
                            break;
                        }
                    }

                    View itemView = inflater.inflate(R.layout.item_rule_paragraph, container, false);
                    TextView tvText = itemView.findViewById(R.id.tvRuleText);
                    if (tvText != null) {
                        setHtmlText(tvText, html);
                    }
                    container.addView(itemView);
                    break;
                }
            }
        }
    }

    private void addHeadingView(LinearLayout container, String html, int level, float density) {
        Context ctx = getContext();
        TextView tvHeading = new TextView(ctx);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        lp.topMargin = container.getChildCount() == 0 ? Math.round(2 * density) : Math.round(12 * density);
        lp.bottomMargin = Math.round(6 * density);
        tvHeading.setLayoutParams(lp);

        float textSizeSp = (level <= 1) ? 16.5f : (level == 2 ? 15.5f : 14.5f);
        tvHeading.setTextSize(textSizeSp);
        tvHeading.setTextColor(ContextCompat.getColor(ctx, R.color.primary_text_color));
        tvHeading.setLinkTextColor(resolveAccentColor(ctx));
        try {
            android.graphics.Typeface boldTf = androidx.core.content.res.ResourcesCompat.getFont(ctx, R.font.open_sans_bold);
            if (boldTf != null) {
                tvHeading.setTypeface(boldTf, android.graphics.Typeface.BOLD);
            } else {
                tvHeading.setTypeface(null, android.graphics.Typeface.BOLD);
            }
        } catch (Exception ignored) {
            tvHeading.setTypeface(null, android.graphics.Typeface.BOLD);
        }

        setHtmlText(tvHeading, html);
        container.addView(tvHeading);
    }

    private void addListItemView(LinearLayout listContainer, ArticleResponse.Node listItem, String marker, String accentHex, float density, int depth) {
        Context ctx = getContext();
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        rowLp.bottomMargin = Math.round(6 * density);
        row.setLayoutParams(rowLp);

        TextView tvMarker = new TextView(ctx);
        LinearLayout.LayoutParams markerLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        markerLp.leftMargin = Math.round(4 * density);
        markerLp.rightMargin = Math.round(8 * density);
        tvMarker.setLayoutParams(markerLp);
        tvMarker.setText(marker);
        tvMarker.setTextSize(14f);
        tvMarker.setTextColor(resolveAccentColor(ctx));
        tvMarker.setTypeface(null, android.graphics.Typeface.BOLD);

        LinearLayout itemBody = new LinearLayout(ctx);
        itemBody.setOrientation(LinearLayout.VERTICAL);
        itemBody.setLayoutParams(new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
        ));

        StringBuilder paragraphAccumulator = new StringBuilder();
        if (listItem.getContent() != null) {
            for (ArticleResponse.Node child : listItem.getContent()) {
                if (child == null) continue;
                String childType = child.getType() != null ? child.getType() : "";
                if ("bulletList".equals(childType) || "orderedList".equals(childType)) {
                    flushListItemParagraph(itemBody, paragraphAccumulator, density);
                    java.util.List<ArticleResponse.Node> single = java.util.Collections.singletonList(child);
                    renderBlockNodes(single, itemBody, depth + 1);
                } else {
                    String part = renderNodeToHtml(child, accentHex).trim();
                    if (!part.isEmpty()) {
                        if (paragraphAccumulator.length() > 0) {
                            paragraphAccumulator.append("<br/>");
                        }
                        paragraphAccumulator.append(part);
                    }
                }
            }
        } else {
            String direct = renderNodeToHtml(listItem, accentHex).trim();
            if (!direct.isEmpty()) {
                paragraphAccumulator.append(direct);
            }
        }
        flushListItemParagraph(itemBody, paragraphAccumulator, density);

        if (itemBody.getChildCount() > 0) {
            row.addView(tvMarker);
            row.addView(itemBody);
            listContainer.addView(row);
        }
    }

    private void flushListItemParagraph(LinearLayout itemBody, StringBuilder accumulator, float density) {
        if (accumulator.length() == 0) return;
        Context ctx = getContext();
        TextView tvItem = new TextView(ctx);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        tvItem.setLayoutParams(lp);
        tvItem.setTextColor(ContextCompat.getColor(ctx, R.color.primary_text_color));
        tvItem.setLinkTextColor(resolveAccentColor(ctx));
        tvItem.setTextSize(14f);
        tvItem.setLineSpacing(3 * density, 1f);
        try {
            android.graphics.Typeface tf = androidx.core.content.res.ResourcesCompat.getFont(ctx, R.font.open_sans);
            if (tf != null) tvItem.setTypeface(tf);
        } catch (Exception ignored) {}

        setHtmlText(tvItem, accumulator.toString());
        itemBody.addView(tvItem);
        accumulator.setLength(0);
    }

    private void setHtmlText(TextView textView, String html) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            textView.setText(Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY));
        } else {
            textView.setText(Html.fromHtml(html));
        }
        textView.setMovementMethod(LinkMovementMethod.getInstance());
    }

    private String renderInlineChildrenToHtml(List<ArticleResponse.Node> children, String accentHex) {
        if (children == null) return "";
        StringBuilder sb = new StringBuilder();
        for (ArticleResponse.Node child : children) {
            sb.append(renderNodeToHtml(child, accentHex));
        }
        return sb.toString();
    }

    private String renderNodeToHtml(ArticleResponse.Node node, String accentHex) {
        if (node == null) return "";
        String type = node.getType() != null ? node.getType() : "";

        switch (type) {
            case "doc":
            case "paragraph":
            case "heading":
            case "listItem":
                String inner = renderInlineChildrenToHtml(node.getContent(), accentHex);
                if (type.equals("paragraph") || type.equals("listItem") || type.equals("heading")) {
                    return formatInlineMarkdownAndRuleNumbers(inner.trim(), accentHex);
                }
                return inner;

            case "text":
                String text = node.getText() != null ? node.getText() : "";
                text = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
                if (node.getMarks() != null) {
                    for (ArticleResponse.Mark mark : node.getMarks()) {
                        if (mark == null || mark.getType() == null) continue;
                        switch (mark.getType()) {
                            case "bold":
                            case "strong":
                                text = "<b>" + text + "</b>";
                                break;
                            case "italic":
                            case "em":
                                text = "<i>" + text + "</i>";
                                break;
                            case "underline":
                                text = "<u>" + text + "</u>";
                                break;
                            case "strike":
                                text = "<s>" + text + "</s>";
                                break;
                            case "code":
                                text = "<tt><font color=\"" + accentHex + "\">" + text + "</font></tt>";
                                break;
                            case "link":
                                String href = (mark.getAttrs() != null && mark.getAttrs().getHref() != null)
                                        ? mark.getAttrs().getHref() : "#";
                                text = "<a href=\"" + href + "\">" + text + "</a>";
                                break;
                        }
                    }
                }
                return text;

            case "hardBreak":
                return "<br/>";

            default:
                return renderInlineChildrenToHtml(node.getContent(), accentHex);
        }
    }

    private String formatInlineMarkdownAndRuleNumbers(String html, String accentHex) {
        if (html == null || html.isEmpty()) return "";
        String formatted = html;
        // Support inline markdown syntax (**bold**, `code`, [text](url))
        formatted = formatted.replaceAll("\\*\\*([^*]+)\\*\\*", "<b>$1</b>");
        formatted = formatted.replaceAll("`([^`]+)`", "<tt><font color=\"" + accentHex + "\">$1</font></tt>");
        formatted = formatted.replaceAll("\\[([^\\]]+)\\]\\((https?://[^)]+)\\)", "<a href=\"$2\">$1</a>");
        // Highlight leading rule numbers like "1.1.", "2.3", "1." in bold accent color if not already styled
        formatted = formatted.replaceFirst(
                "^(\\d+(?:\\.\\d+)+\\.?|\\d+\\))\\s+",
                "<b><font color=\"" + accentHex + "\">$1</font></b> "
        );
        return formatted;
    }

    private int resolveAccentColor(Context context) {
        android.util.TypedValue tv = new android.util.TypedValue();
        if (context.getTheme().resolveAttribute(R.attr.secondaryColor, tv, true)) {
            return tv.data;
        }
        return ContextCompat.getColor(context, R.color.purple_primary);
    }

    private String getAccentColorHex(Context context) {
        int color = resolveAccentColor(context) & 0x00FFFFFF;
        return String.format("#%06X", color);
    }
}
