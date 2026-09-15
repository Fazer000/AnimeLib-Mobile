package com.example.animelib.adapters;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import com.example.animelib.R;
import com.example.animelib.models.RelatedTitlesResponse;
import com.example.animelib.util.ImageLoader;

import java.util.List;

/**
 * Адаптер для горизонтальной карусели связанных тайтлов
 */
public class HorizontalRelatedTitlesAdapter extends RecyclerView.Adapter<HorizontalRelatedTitlesAdapter.RelatedTitleViewHolder> {

    private List<RelatedTitlesResponse.RelatedTitle> relatedTitles;
    private final OnRelatedTitleSelectedListener listener;

    public interface OnRelatedTitleSelectedListener {
        /**
         * Вызывается при клике на связанный тайтл
         * @param media Объект медиа для навигации
         */
        void onRelatedTitleSelected(RelatedTitlesResponse.Media media);
    }

    public HorizontalRelatedTitlesAdapter(List<RelatedTitlesResponse.RelatedTitle> relatedTitles,
                                        OnRelatedTitleSelectedListener listener) {
        this.relatedTitles = filterValidTitles(relatedTitles);
        this.listener = listener;
    }

    @NonNull
    @Override
    public RelatedTitleViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_related_title, parent, false);
        return new RelatedTitleViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull RelatedTitleViewHolder holder, int position) {
        RelatedTitlesResponse.RelatedTitle relatedTitle = relatedTitles.get(position);
        RelatedTitlesResponse.Media media = relatedTitle.getMedia();

        // media не может быть null, т.к. мы фильтруем список в filterValidTitles()
        String relatedType = getRelatedType(relatedTitle);
        holder.relatedTypeText.setText(relatedType);

        // Устанавливаем название (приоритет: русское -> английское -> оригинальное)
        String title = getDisplayTitle(media);
        holder.titleText.setText(title);

        // Устанавливаем тип и статус
        String typeStatus = buildTypeStatusString(media);
        holder.typeStatusText.setText(typeStatus);

        // Загружаем обложку
        loadCoverImage(holder.coverImage, media);

        android.util.Log.d("HorizontalRelatedAdapter", "Binding item [" + position + "]: " +
                "title=" + title + 
                ", model=" + media.getModel() + 
                ", slugUrl=" + media.getSlugUrl());
        
        // Разблокированы все карточки (аниме, манга, ранобэ и т.д.)
        holder.itemView.setAlpha(1.0f);
        holder.itemView.setEnabled(true);
        holder.itemView.setOnClickListener(v -> {
            android.util.Log.d("HorizontalRelatedAdapter", "Related title clicked: " + title +
                    " (model=" + media.getModel() + ", slugUrl=" + media.getSlugUrl() + ")");
            if (listener != null) {
                listener.onRelatedTitleSelected(media);
            }
        });
    }

    @Override
    public int getItemCount() {
        return relatedTitles != null ? relatedTitles.size() : 0;
    }

    public void updateData(List<RelatedTitlesResponse.RelatedTitle> newRelatedTitles) {
        this.relatedTitles = filterValidTitles(newRelatedTitles);
        notifyDataSetChanged();
    }

    /**
     * Фильтрует список, оставляя только тайтлы с валидным media
     */
    private List<RelatedTitlesResponse.RelatedTitle> filterValidTitles(List<RelatedTitlesResponse.RelatedTitle> titles) {
        if (titles == null) {
            return null;
        }
        
        List<RelatedTitlesResponse.RelatedTitle> filtered = new java.util.ArrayList<>();
        for (RelatedTitlesResponse.RelatedTitle title : titles) {
            if (title != null && title.getMedia() != null) {
                filtered.add(title);
            }
        }
        return filtered;
    }

    private String getRelatedType(RelatedTitlesResponse.RelatedTitle relatedTitle) {
        if (relatedTitle.getRelatedType().getLabel() != null) {
            return relatedTitle.getRelatedType().getLabel();
        } else {
            return "";
        }
    }

    /**
     * Формирует полный веб-URL для тайтла (аниме, манга, ранобэ и т.д.)
     */
    public static String buildWebUrl(RelatedTitlesResponse.Media media, android.content.Context context) {
        if (media == null) return null;
        String slugUrl = media.getSlugUrl();
        if (slugUrl == null || slugUrl.trim().isEmpty()) {
            slugUrl = media.getSlug();
        }
        if (slugUrl == null || slugUrl.trim().isEmpty()) {
            return null;
        }
        slugUrl = slugUrl.trim();
        if (slugUrl.startsWith("http://") || slugUrl.startsWith("https://")) {
            return slugUrl;
        }

        String model = media.getModel() != null && !media.getModel().trim().isEmpty() 
                ? media.getModel().trim().toLowerCase() : "";
        int site = media.getSite();

        String path = slugUrl;
        if (!path.startsWith("/")) {
            path = "/" + path;
        }

        boolean isManga = "manga".equals(model) || model.contains("manga") || site == 1 || path.contains("/manga/");
        boolean isRanobe = "ranobe".equals(model) || "novel".equals(model) || model.contains("ranobe") || site == 2 || path.contains("/ranobe/");

        if (!path.startsWith("/ru/")) {
            if (path.startsWith("/manga/") || path.startsWith("/ranobe/") || path.startsWith("/anime/")) {
                path = "/ru" + path;
            } else {
                String targetModel = isManga ? "manga" : (isRanobe ? "ranobe" : (!model.isEmpty() ? model : "anime"));
                path = "/ru/" + targetModel + path;
            }
        }

        String mainSiteUrl = "https://v5.animelib.org";
        if (context != null) {
            try {
                com.example.animelib.data.DatabaseManager db = new com.example.animelib.data.DatabaseManager(context);
                String dbUrl = db.getSiteUrl();
                if (dbUrl != null && !dbUrl.trim().isEmpty()) {
                    mainSiteUrl = dbUrl;
                }
            } catch (Exception e) {
                android.util.Log.e("RelatedTitlesAdapter", "Failed to get siteUrl from DB", e);
            }
        }

        boolean isOtherCountries = com.example.animelib.util.SiteUtils.isOtherRegion(mainSiteUrl, context);

        String baseUrl;
        if (isManga) {
            baseUrl = isOtherCountries ? "https://mangalib.org" : "https://mangalib.me";
        } else if (isRanobe) {
            baseUrl = "https://ranobelib.me";
        } else {
            baseUrl = mainSiteUrl;
        }
        return baseUrl + path;
    }

    public static String buildWebUrl(RelatedTitlesResponse.Media media) {
        return buildWebUrl(media, null);
    }

    /**
     * Получает отображаемое название тайтла
     */
    public static String getDisplayTitle(RelatedTitlesResponse.Media media) {
        if (media == null) return "Без названия";
        if (media.getRusName() != null && !media.getRusName().trim().isEmpty()) {
            return media.getRusName();
        }
        if (media.getEngName() != null && !media.getEngName().trim().isEmpty()) {
            return media.getEngName();
        }
        if (media.getName() != null && !media.getName().trim().isEmpty()) {
            return media.getName();
        }
        return "Без названия";
    }

    /**
     * Строит строку с типом и статусом
     */
    private String buildTypeStatusString(RelatedTitlesResponse.Media media) {
        StringBuilder result = new StringBuilder();

        // Добавляем тип
        if (media.getType() != null && media.getType().getLabel() != null) {
            result.append(media.getType().getLabel());
        }

        // Добавляем статус
        if (media.getStatus() != null && media.getStatus().getLabel() != null) {
            if (result.length() > 0) {
                result.append(" · ");
            }
            result.append(media.getStatus().getLabel());
        }

        return result.toString();
    }

    /**
     * Загружает обложку тайтла
     */
    private void loadCoverImage(ImageView imageView, RelatedTitlesResponse.Media media) {
        if (media.getCover() == null) {
            imageView.setImageResource(R.color.gray_color);
            return;
        }

        String imageUrl = getImageUrl(media);

        if (imageUrl != null && !imageUrl.trim().isEmpty()) {
            ImageLoader.getInstance().loadInto(imageView, imageUrl, R.color.gray_color);
        } else {
            imageView.setImageResource(R.color.gray_color);
        }
    }

    @Nullable
    private static String getImageUrl(RelatedTitlesResponse.Media media) {
        String imageUrl = null;
        RelatedTitlesResponse.Cover cover = media.getCover();

        // Приоритет: default -> md -> thumbnail
        if (cover.getDefaultUrl() != null && !cover.getDefaultUrl().trim().isEmpty()) {
            imageUrl = cover.getDefaultUrl();
        } else if (cover.getMd() != null && !cover.getMd().trim().isEmpty()) {
            imageUrl = cover.getMd();
        } else if (cover.getThumbnail() != null && !cover.getThumbnail().trim().isEmpty()) {
            imageUrl = cover.getThumbnail();
        }
        return imageUrl;
    }

    /**
     * ViewHolder для элемента связанного тайтла
     */
    public static class RelatedTitleViewHolder extends RecyclerView.ViewHolder {
        ImageView coverImage;
        TextView titleText;
        TextView typeStatusText;
        TextView relatedTypeText;

        RelatedTitleViewHolder(@NonNull View itemView) {
            super(itemView);
            coverImage = itemView.findViewById(R.id.coverImage);
            titleText = itemView.findViewById(R.id.titleText);
            typeStatusText = itemView.findViewById(R.id.typeStatusText);
            relatedTypeText = itemView.findViewById(R.id.relatedTypeText);
        }
    }
}
