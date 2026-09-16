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
        LayoutInflater inflater = LayoutInflater.from(getContext());

        for (ArticleResponse.Node node : nodes) {
            String html = renderNodeToHtml(node);
            if (html == null || html.trim().isEmpty()) continue;

            View itemCardView = inflater.inflate(R.layout.item_rule_paragraph, rulesContentContainer, false);
            TextView tvText = itemCardView.findViewById(R.id.tvRuleText);

            if (tvText != null) {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                    tvText.setText(Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY));
                } else {
                    tvText.setText(Html.fromHtml(html));
                }
                tvText.setMovementMethod(LinkMovementMethod.getInstance());
            }

            rulesContentContainer.addView(itemCardView);
        }
    }

    private String renderNodeToHtml(ArticleResponse.Node node) {
        if (node == null) return "";
        String type = node.getType() != null ? node.getType() : "";

        switch (type) {
            case "doc":
            case "paragraph":
            case "heading":
            case "bulletList":
            case "orderedList":
            case "listItem":
                StringBuilder sb = new StringBuilder();
                if (node.getContent() != null) {
                    for (ArticleResponse.Node child : node.getContent()) {
                        sb.append(renderNodeToHtml(child));
                    }
                }
                String inner = sb.toString();
                if (type.equals("paragraph") || type.equals("listItem") || type.equals("heading")) {
                    return inner.trim();
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
                                text = "<b>" + text + "</b>";
                                break;
                            case "italic":
                                text = "<i>" + text + "</i>";
                                break;
                            case "underline":
                                text = "<u>" + text + "</u>";
                                break;
                            case "strike":
                                text = "<s>" + text + "</s>";
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
                StringBuilder defSb = new StringBuilder();
                if (node.getContent() != null) {
                    for (ArticleResponse.Node child : node.getContent()) {
                        defSb.append(renderNodeToHtml(child));
                    }
                }
                return defSb.toString();
        }
    }
}
