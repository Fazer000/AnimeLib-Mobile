package com.example.animelib.util;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.net.URI;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Парсер HLS (.m3u8) плейлистов: выбор нужного качества в master-плейлисте,
 * разбор медиа-плейлиста с поддержкой #EXT-X-MAP (fMP4 init) и #EXT-X-KEY (AES-128).
 */
public final class HlsPlaylistParser {

    private static final Pattern RESOLUTION_PATTERN = Pattern.compile("RESOLUTION=\\s*(\\d+)x(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern BANDWIDTH_PATTERN = Pattern.compile("BANDWIDTH=\\s*(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern URI_ATTR_PATTERN = Pattern.compile("URI=\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern METHOD_ATTR_PATTERN = Pattern.compile("METHOD=([^,\\s]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern IV_ATTR_PATTERN = Pattern.compile("IV=0[xX]([0-9a-fA-F]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern DIGITS_PATTERN = Pattern.compile("(\\d+)");

    private HlsPlaylistParser() {
    }

    public static final class StreamVariant {
        public final String url;
        public final int width;
        public final int height;
        public final long bandwidth;

        public StreamVariant(String url, int width, int height, long bandwidth) {
            this.url = url;
            this.width = width;
            this.height = height;
            this.bandwidth = bandwidth;
        }
    }

    public static final class HlsSegment {
        public final int index;
        public final int mediaSequence;
        public final String url;
        public final String encryptionMethod; // "NONE" or "AES-128"
        @Nullable public final String keyUrl;
        @Nullable public final byte[] iv;

        public HlsSegment(int index, int mediaSequence, String url,
                          String encryptionMethod, @Nullable String keyUrl, @Nullable byte[] iv) {
            this.index = index;
            this.mediaSequence = mediaSequence;
            this.url = url;
            this.encryptionMethod = encryptionMethod;
            this.keyUrl = keyUrl;
            this.iv = iv;
        }

        public boolean isAes128Encrypted() {
            return "AES-128".equalsIgnoreCase(encryptionMethod) && keyUrl != null && !keyUrl.isEmpty();
        }
    }

    public static final class ParsedMediaPlaylist {
        @Nullable public final String initSegmentUrl;
        public final List<HlsSegment> segments;

        public ParsedMediaPlaylist(@Nullable String initSegmentUrl, List<HlsSegment> segments) {
            this.initSegmentUrl = initSegmentUrl;
            this.segments = segments;
        }
    }

    public static boolean isMasterPlaylist(@Nullable String playlistContent) {
        return playlistContent != null && playlistContent.contains("#EXT-X-STREAM-INF");
    }

    /**
     * Выбирает URL медиа-плейлиста из master-плейлиста, наиболее точно соответствующий preferredQuality.
     * Приоритет: точное совпадение высоты -> ближайшая меньшая высота -> ближайшая большая высота -> максимальный bandwidth.
     */
    @Nullable
    public static String selectBestVariantUrl(@Nullable String masterPlaylist,
                                              @NonNull String baseUrl,
                                              @Nullable String preferredQuality) {
        if (masterPlaylist == null || masterPlaylist.trim().isEmpty()) {
            return null;
        }

        List<StreamVariant> variants = parseMasterVariants(masterPlaylist, baseUrl);
        if (variants.isEmpty()) {
            return null;
        }

        int targetHeight = parseTargetHeight(preferredQuality);
        if (targetHeight > 0) {
            StreamVariant exact = null;
            StreamVariant bestLower = null;
            StreamVariant bestHigher = null;

            for (StreamVariant v : variants) {
                if (v.height <= 0) continue;
                if (v.height == targetHeight) {
                    if (exact == null || v.bandwidth > exact.bandwidth) {
                        exact = v;
                    }
                } else if (v.height < targetHeight) {
                    if (bestLower == null
                            || v.height > bestLower.height
                            || (v.height == bestLower.height && v.bandwidth > bestLower.bandwidth)) {
                        bestLower = v;
                    }
                } else {
                    if (bestHigher == null
                            || v.height < bestHigher.height
                            || (v.height == bestHigher.height && v.bandwidth > bestHigher.bandwidth)) {
                        bestHigher = v;
                    }
                }
            }

            if (exact != null) return exact.url;
            if (bestLower != null) return bestLower.url;
            if (bestHigher != null) return bestHigher.url;
        }

        // Fallback: максимальное разрешение, затем максимальный битрейт
        StreamVariant best = variants.get(0);
        for (int i = 1; i < variants.size(); i++) {
            StreamVariant v = variants.get(i);
            if (v.height > best.height || (v.height == best.height && v.bandwidth > best.bandwidth)) {
                best = v;
            }
        }
        return best.url;
    }

    @NonNull
    public static List<StreamVariant> parseMasterVariants(@NonNull String masterPlaylist, @NonNull String baseUrl) {
        List<StreamVariant> variants = new ArrayList<>();
        String[] lines = masterPlaylist.split("\\r?\\n");
        boolean awaitingUri = false;
        int pendingWidth = 0;
        int pendingHeight = 0;
        long pendingBandwidth = 0;

        for (String rawLine : lines) {
            String line = rawLine.trim();
            if (line.isEmpty()) continue;

            if (line.startsWith("#EXT-X-STREAM-INF")) {
                awaitingUri = true;
                pendingWidth = 0;
                pendingHeight = 0;
                pendingBandwidth = 0;

                Matcher resMatcher = RESOLUTION_PATTERN.matcher(line);
                if (resMatcher.find()) {
                    try {
                        pendingWidth = Integer.parseInt(resMatcher.group(1));
                        pendingHeight = Integer.parseInt(resMatcher.group(2));
                    } catch (NumberFormatException ignored) {
                    }
                }
                Matcher bwMatcher = BANDWIDTH_PATTERN.matcher(line);
                if (bwMatcher.find()) {
                    try {
                        pendingBandwidth = Long.parseLong(bwMatcher.group(1));
                    } catch (NumberFormatException ignored) {
                    }
                }
            } else if (!line.startsWith("#")) {
                String resolved = resolveUrl(baseUrl, line);
                if (awaitingUri) {
                    variants.add(new StreamVariant(resolved, pendingWidth, pendingHeight, pendingBandwidth));
                    awaitingUri = false;
                } else {
                    variants.add(new StreamVariant(resolved, 0, 0, 0));
                }
            }
        }
        return variants;
    }

    /**
     * Парсит медиа-плейлист: извлекает #EXT-X-MAP (если есть), #EXT-X-KEY (AES-128) и список сегментов.
     */
    @NonNull
    public static ParsedMediaPlaylist parseMediaPlaylist(@NonNull String mediaPlaylist, @NonNull String mediaPlaylistUrl) {
        List<HlsSegment> segments = new ArrayList<>();
        String initSegmentUrl = null;

        int mediaSequence = 0;
        String currentMethod = "NONE";
        String currentKeyUrl = null;
        byte[] explicitIv = null;

        String[] lines = mediaPlaylist.split("\\r?\\n");
        int segIndex = 0;

        for (String rawLine : lines) {
            String line = rawLine.trim();
            if (line.isEmpty()) continue;

            if (line.startsWith("#EXT-X-MEDIA-SEQUENCE:")) {
                try {
                    mediaSequence = Integer.parseInt(line.substring("#EXT-X-MEDIA-SEQUENCE:".length()).trim());
                } catch (NumberFormatException ignored) {
                }
            } else if (line.startsWith("#EXT-X-MAP:")) {
                Matcher uriMatcher = URI_ATTR_PATTERN.matcher(line);
                if (uriMatcher.find()) {
                    initSegmentUrl = resolveUrl(mediaPlaylistUrl, uriMatcher.group(1));
                }
            } else if (line.startsWith("#EXT-X-KEY:")) {
                Matcher methodMatcher = METHOD_ATTR_PATTERN.matcher(line);
                if (methodMatcher.find()) {
                    currentMethod = methodMatcher.group(1).trim().toUpperCase(Locale.ROOT);
                }
                if ("NONE".equals(currentMethod)) {
                    currentKeyUrl = null;
                    explicitIv = null;
                } else {
                    Matcher uriMatcher = URI_ATTR_PATTERN.matcher(line);
                    if (uriMatcher.find()) {
                        currentKeyUrl = resolveUrl(mediaPlaylistUrl, uriMatcher.group(1));
                    }
                    Matcher ivMatcher = IV_ATTR_PATTERN.matcher(line);
                    if (ivMatcher.find()) {
                        explicitIv = parseHexIv(ivMatcher.group(1));
                    } else {
                        explicitIv = null;
                    }
                }
            } else if (!line.startsWith("#")) {
                String segUrl = resolveUrl(mediaPlaylistUrl, line);
                int seqNum = mediaSequence + segIndex;
                byte[] effectiveIv = null;
                if ("AES-128".equals(currentMethod)) {
                    effectiveIv = explicitIv != null ? explicitIv : sequenceNumberToIv(seqNum);
                }
                segments.add(new HlsSegment(segIndex, seqNum, segUrl, currentMethod, currentKeyUrl, effectiveIv));
                segIndex++;
            }
        }

        return new ParsedMediaPlaylist(initSegmentUrl, segments);
    }

    /**
     * Разрешает относительный URL относительно базового URL, корректно игнорируя query/fragment части базы.
     */
    @NonNull
    public static String resolveUrl(@NonNull String baseUrl, @Nullable String relativeUrl) {
        if (relativeUrl == null || relativeUrl.trim().isEmpty()) {
            return baseUrl;
        }
        String rel = relativeUrl.trim();
        if (rel.startsWith("http://") || rel.startsWith("https://")) {
            return rel;
        }
        if (rel.startsWith("//")) {
            return "https:" + rel;
        }
        try {
            URI baseUri = URI.create(baseUrl);
            URI resolved = baseUri.resolve(rel);
            return resolved.toString();
        } catch (Exception ignored) {
        }

        // Safe manual fallback if URL contains unescaped characters
        String cleanBase = baseUrl;
        int qIdx = cleanBase.indexOf('?');
        if (qIdx >= 0) cleanBase = cleanBase.substring(0, qIdx);
        int hIdx = cleanBase.indexOf('#');
        if (hIdx >= 0) cleanBase = cleanBase.substring(0, hIdx);

        if (rel.startsWith("/")) {
            int schemeEnd = cleanBase.indexOf("://");
            if (schemeEnd >= 0) {
                int hostEnd = cleanBase.indexOf('/', schemeEnd + 3);
                String origin = hostEnd >= 0 ? cleanBase.substring(0, hostEnd) : cleanBase;
                return origin + rel;
            }
            return "https://kodik.info" + rel;
        }
        int lastSlash = cleanBase.lastIndexOf('/');
        if (lastSlash != -1) {
            return cleanBase.substring(0, lastSlash + 1) + rel;
        }
        return rel;
    }

    public static byte[] decryptAes128(byte[] encryptedData, byte[] keyBytes, byte[] ivBytes) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        SecretKeySpec keySpec = new SecretKeySpec(keyBytes, "AES");
        IvParameterSpec ivSpec = new IvParameterSpec(ivBytes);
        cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec);
        return cipher.doFinal(encryptedData);
    }

    public static byte[] sequenceNumberToIv(int sequenceNumber) {
        ByteBuffer buf = ByteBuffer.allocate(16);
        buf.putLong(0L);
        buf.putLong(sequenceNumber & 0xFFFFFFFFL);
        return buf.array();
    }

    private static byte[] parseHexIv(String hex) {
        String clean = hex.trim();
        while (clean.length() < 32) {
            clean = "0" + clean;
        }
        if (clean.length() > 32) {
            clean = clean.substring(clean.length() - 32);
        }
        byte[] out = new byte[16];
        for (int i = 0; i < 16; i++) {
            int hi = Character.digit(clean.charAt(i * 2), 16);
            int lo = Character.digit(clean.charAt(i * 2 + 1), 16);
            out[i] = (byte) (((hi >= 0 ? hi : 0) << 4) | (lo >= 0 ? lo : 0));
        }
        return out;
    }

    private static int parseTargetHeight(@Nullable String preferredQuality) {
        if (preferredQuality == null || preferredQuality.trim().isEmpty()) {
            return 0;
        }
        Matcher m = DIGITS_PATTERN.matcher(preferredQuality);
        if (m.find()) {
            try {
                return Integer.parseInt(m.group(1));
            } catch (NumberFormatException ignored) {
            }
        }
        return 0;
    }
}
