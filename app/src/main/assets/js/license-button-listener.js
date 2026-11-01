/**
 * Обработчик замены кнопки "Лицензирован" / "Лицензировано" на полноценную кнопку "Смотреть" (<a>)
 * и перехвата перехода в нативный плеер.
 */
(function () {
    var LICENSED_TEXT_REGEX = /^лицензирован[оаы]?$/i;

    var PLAY_SVG_HTML = '<svg class="svg-inline--fa fa-play fa-fw" aria-hidden="true" focusable="false" data-prefix="fas" data-icon="play" role="img" xmlns="http://www.w3.org/2000/svg" viewBox="0 0 384 512"><path class="" fill="currentColor" d="M73 39c-14.8-9.1-33.4-9.4-48.5-.9S0 62.6 0 80L0 432c0 17.4 9.4 33.4 24.5 41.9s33.7 8.1 48.5-.9L361 297c14.3-8.7 23-24.2 23-41s-8.7-32.2-23-41L73 39z"></path></svg>';

    function isAnimelibSite() {
        var host = (window.location && window.location.hostname) ? window.location.hostname.toLowerCase() : '';
        return host.indexOf('animelib') !== -1 || host.indexOf('anilib') !== -1;
    }

    function extractAnimeRouteInfo(contextEl) {
        var pathname = (window.location && window.location.pathname) ? window.location.pathname : '';
        var m = pathname.match(/^((?:\/[a-z]{2})?\/anime\/([^\/?#]+))/i);
        if (m) {
            return { basePath: m[1], slug: m[2] };
        }
        var mDirect = pathname.match(/^((?:\/[a-z]{2})?\/(\d+--[^\/?#]+))/i);
        if (mDirect) {
            return { basePath: mDirect[1], slug: mDirect[2] };
        }

        if (contextEl && contextEl.closest) {
            var container = contextEl.closest('[class*="page"], main, body') || document;
            var links = container.querySelectorAll ? container.querySelectorAll('a[href*="/anime/"]') : [];
            for (var i = 0; i < links.length; i++) {
                var rawHref = links[i].getAttribute('href') || '';
                var linkMatch = rawHref.match(/((?:\/[a-z]{2})?\/anime\/([^\/?#]+))/i);
                if (linkMatch && linkMatch[2] !== 'watch') {
                    return { basePath: linkMatch[1], slug: linkMatch[2] };
                }
            }
        }

        var cleanPath = pathname.replace(/\/watch\/?$/i, '').replace(/\/+$/, '');
        var parts = cleanPath.split('/');
        var fallbackSlug = parts.length > 0 ? parts[parts.length - 1] : '';
        return { basePath: cleanPath, slug: fallbackSlug };
    }

    function findLatestViewForAnime(animeId, animeSlug) {
        var rawSources = [];
        try {
            if (typeof localStorage !== 'undefined') {
                var lsVal = localStorage.getItem('latest-views');
                if (lsVal && lsVal !== '[]' && lsVal !== 'null') {
                    rawSources.push(lsVal);
                }
            }
        } catch (e) {}

        try {
            if (window.AndroidInterface && typeof window.AndroidInterface.getLatestViewsJson === 'function') {
                var nativeVal = window.AndroidInterface.getLatestViewsJson();
                if (nativeVal && nativeVal !== '[]' && nativeVal !== 'null') {
                    rawSources.push(nativeVal);
                }
            }
        } catch (e) {}

        var lowerSlug = (animeSlug || '').toLowerCase();
        for (var s = 0; s < rawSources.length; s++) {
            try {
                var list = JSON.parse(rawSources[s]);
                if (!Array.isArray(list)) continue;
                for (var i = 0; i < list.length; i++) {
                    var item = list[i];
                    if (!item || !item.media) continue;
                    var media = item.media;
                    var mediaId = media.id ? parseInt(media.id, 10) : 0;
                    var mediaSlugUrl = (media.slug_url || '').toLowerCase();
                    var mediaSlug = (media.slug || '').toLowerCase();

                    if ((animeId > 0 && mediaId === animeId) ||
                        (lowerSlug && (mediaSlugUrl === lowerSlug || mediaSlug === lowerSlug))) {
                        return item;
                    }
                }
            } catch (e) {}
        }
        return null;
    }

    function buildWatchHref(contextEl) {
        var info = extractAnimeRouteInfo(contextEl);
        var basePath = info.basePath || '';
        var slug = info.slug || '';
        if (!basePath) {
            return (window.location && window.location.href) ? window.location.href : '/watch';
        }

        var idMatch = slug.match(/^(\d+)/);
        var animeId = idMatch ? parseInt(idMatch[1], 10) : 0;

        var watchHref = basePath + '/watch';
        var latestEntry = findLatestViewForAnime(animeId, slug);
        if (latestEntry) {
            var epId = (latestEntry.meta && latestEntry.meta.episode)
                ? latestEntry.meta.episode
                : (latestEntry.item && latestEntry.item.id ? latestEntry.item.id : null);
            var player = (latestEntry.meta && latestEntry.meta.player) ? latestEntry.meta.player : 'Animelib';
            var team = (latestEntry.meta && latestEntry.meta.team) ? latestEntry.meta.team : null;

            if (epId) {
                var params = ['episode=' + encodeURIComponent(String(epId))];
                if (player) {
                    params.push('player=' + encodeURIComponent(String(player)));
                }
                if (team && String(team) !== '0') {
                    params.push('team=' + encodeURIComponent(String(team)));
                }
                watchHref += '?' + params.join('&');
            }
        }
        return watchHref;
    }

    function isLicensedButton(button) {
        if (!button || button.nodeType !== 1 || button.tagName !== 'BUTTON') {
            return false;
        }
        var spans = button.querySelectorAll ? button.querySelectorAll('span') : [];
        for (var i = 0; i < spans.length; i++) {
            var st = (spans[i].textContent || '').replace(/\s+/g, ' ').trim();
            if (LICENSED_TEXT_REGEX.test(st)) {
                return true;
            }
        }
        var fullText = (button.textContent || '').replace(/\s+/g, ' ').trim();
        return LICENSED_TEXT_REGEX.test(fullText);
    }

    function launchNativePlayer(targetUrl) {
        if (!targetUrl) {
            targetUrl = window.location.href;
        }
        if (window.AndroidInterface && typeof window.AndroidInterface.onPlayerButtonClicked === 'function') {
            window.AndroidInterface.onPlayerButtonClicked(targetUrl);
        }
    }

    function buildAnchorClassName(button) {
        var raw = (typeof button.className === 'string') ? button.className.trim().split(/\s+/) : [];
        var extra = [];
        for (var i = 0; i < raw.length; i++) {
            var c = raw[i];
            if (!c || c === 'btn' || c === 'variant-default' || c === 'variant-secondary' ||
                c === 'is-filled' || c === 'variant-primary' || c === 'is-disabled' || c === 'disabled') {
                continue;
            }
            if (extra.indexOf(c) === -1) {
                extra.push(c);
            }
        }
        if (extra.length === 0) {
            extra.push('at1_b3');
        }
        return ['btn', 'is-filled', 'variant-primary'].concat(extra).join(' ');
    }

    if (!window.__animelibLicensedAnchors) {
        window.__animelibLicensedAnchors = [];
    }

    function cleanupOrUpdateTrackedAnchors(restoreOnSlugChange) {
        var list = window.__animelibLicensedAnchors;
        if (!Array.isArray(list) || list.length === 0) return;

        var currentInfo = extractAnimeRouteInfo(null);
        var currentSlug = currentInfo.slug || '';

        for (var i = list.length - 1; i >= 0; i--) {
            var anchor = list[i];
            if (!anchor || !anchor.parentNode) {
                list.splice(i, 1);
                continue;
            }

            // Если перешли по SPA на другой тайтл/страницу — возвращаем оригинальный <button> на место для чистого патча Vue VDOM
            if (restoreOnSlugChange && anchor.__animeSlug && anchor.__animeSlug !== currentSlug) {
                try {
                    if (anchor.__originalLicensedButton) {
                        anchor.parentNode.replaceChild(anchor.__originalLicensedButton, anchor);
                    } else {
                        anchor.remove();
                    }
                } catch (e) {}
                list.splice(i, 1);
                continue;
            }

            // Защита от дубля, если сайт сам отрендерил нативную кнопку /watch рядом
            var parent = anchor.parentNode;
            var siblingLinks = parent.querySelectorAll ? parent.querySelectorAll('a[href*="/watch"]') : [];
            var hasNativeWatchSibling = false;
            for (var j = 0; j < siblingLinks.length; j++) {
                if (siblingLinks[j] !== anchor && !siblingLinks[j].__animelibLicensedWatch) {
                    hasNativeWatchSibling = true;
                    break;
                }
            }
            if (hasNativeWatchSibling) {
                try { anchor.remove(); } catch (e) {}
                list.splice(i, 1);
                continue;
            }

            // Актуализируем href (например, после синхронизации latest-views)
            var freshHref = buildWatchHref(anchor);
            if (freshHref && anchor.getAttribute('href') !== freshHref) {
                anchor.setAttribute('href', freshHref);
            }
        }
    }

    function replaceLicensedButton(button) {
        if (!isLicensedButton(button)) return null;
        var parent = button.parentNode;
        if (!parent) return null;

        var routeInfo = extractAnimeRouteInfo(button);
        var href = buildWatchHref(button);

        var anchor = document.createElement('a');
        anchor.setAttribute('href', href);
        anchor.className = buildAnchorClassName(button);
        anchor.setAttribute('disabled', 'false');
        anchor.setAttribute('type', button.getAttribute('type') || 'button');
        anchor.setAttribute('media', button.hasAttribute('media') ? (button.getAttribute('media') || '[object Object]') : '[object Object]');

        if (button.attributes) {
            for (var a = 0; a < button.attributes.length; a++) {
                var attr = button.attributes[a];
                if (attr && attr.name && attr.name.indexOf('data-v-') === 0) {
                    anchor.setAttribute(attr.name, attr.value);
                }
            }
        }

        var existingSvg = button.querySelector('svg');
        var svgHtml = existingSvg ? existingSvg.outerHTML : PLAY_SVG_HTML;
        anchor.innerHTML = '<!---->' + svgHtml + '<span class="at1_jw at1_at2" data-bookmark="true">Смотреть</span><!----><!---->';

        anchor.__animelibLicensedWatch = true;
        anchor.__originalLicensedButton = button;
        anchor.__animeSlug = routeInfo.slug || '';

        anchor.addEventListener('click', function (e) {
            e.preventDefault();
            e.stopPropagation();
            if (e.stopImmediatePropagation) {
                e.stopImmediatePropagation();
            }
            var updatedHref = buildWatchHref(anchor) || anchor.getAttribute('href') || window.location.href;
            anchor.setAttribute('href', updatedHref);
            launchNativePlayer(anchor.href || updatedHref);
        }, true);

        // Удаляем прежний заменённый якорь в том же контейнере, если был
        var oldLinks = parent.querySelectorAll ? parent.querySelectorAll('a') : [];
        for (var k = 0; k < oldLinks.length; k++) {
            if (oldLinks[k] !== anchor && oldLinks[k].__animelibLicensedWatch) {
                try { oldLinks[k].remove(); } catch (err) {}
            }
        }

        parent.replaceChild(anchor, button);
        window.__animelibLicensedAnchors.push(anchor);
        return anchor;
    }

    function scanAndReplaceAll() {
        if (!isAnimelibSite()) return;
        cleanupOrUpdateTrackedAnchors(false);
        var buttons = document.querySelectorAll ? document.querySelectorAll('button') : [];
        for (var i = 0; i < buttons.length; i++) {
            if (isLicensedButton(buttons[i])) {
                replaceLicensedButton(buttons[i]);
            }
        }
    }

    var rafPending = false;
    function scheduleScan() {
        if (rafPending) return;
        rafPending = true;
        var run = function () {
            rafPending = false;
            scanAndReplaceAll();
        };
        if (typeof requestAnimationFrame === 'function') {
            requestAnimationFrame(run);
        } else {
            setTimeout(run, 16);
        }
    }

    function setupForCurrentDocument() {
        if (!isAnimelibSite()) return;

        // 1. Capture-обработчик клика на уровне документа (перехватывает и заменённую <a>, и ещё не заменённый <button>)
        if (!document.__animelibLicenseClickSetup) {
            document.__animelibLicenseClickSetup = true;
            document.addEventListener('click', function (e) {
                if (!isAnimelibSite()) return;
                var startEl = (e.target && e.target.nodeType === 1) ? e.target : (e.target ? e.target.parentElement : null);
                if (!startEl || typeof startEl.closest !== 'function') return;

                var customAnchor = startEl.closest('a');
                if (customAnchor && customAnchor.__animelibLicensedWatch) {
                    e.preventDefault();
                    e.stopPropagation();
                    if (e.stopImmediatePropagation) e.stopImmediatePropagation();
                    var updatedHref = buildWatchHref(customAnchor) || customAnchor.getAttribute('href') || window.location.href;
                    customAnchor.setAttribute('href', updatedHref);
                    launchNativePlayer(customAnchor.href || updatedHref);
                    return;
                }

                var btn = startEl.closest('button');
                if (btn && isLicensedButton(btn)) {
                    e.preventDefault();
                    e.stopPropagation();
                    if (e.stopImmediatePropagation) e.stopImmediatePropagation();
                    var replaced = replaceLicensedButton(btn);
                    var targetUrl = replaced ? (replaced.href || replaced.getAttribute('href')) : buildWatchHref(btn);
                    launchNativePlayer(targetUrl || window.location.href);
                }
            }, true);
        }

        // 2. MutationObserver на корневом элементе документа (ловит и добавление узлов, и обновление текста Vue)
        var rootNode = document.documentElement || document.body;
        if (rootNode && !rootNode.__animelibLicenseObserverSetup && typeof MutationObserver !== 'undefined') {
            rootNode.__animelibLicenseObserverSetup = true;
            var observer = new MutationObserver(function () {
                scheduleScan();
            });
            observer.observe(rootNode, {
                childList: true,
                subtree: true,
                characterData: true
            });
        }

        // 3. Перехват SPA-навигации (Vue Router pushState / replaceState / popstate)
        if (!window.__animelibLicenseHistoryHooked && window.history) {
            window.__animelibLicenseHistoryHooked = true;
            var onRouteWillChange = function () {
                cleanupOrUpdateTrackedAnchors(true);
                Promise.resolve().then(scheduleScan);
                setTimeout(scanAndReplaceAll, 80);
                setTimeout(scanAndReplaceAll, 250);
            };

            var origPushState = window.history.pushState;
            if (typeof origPushState === 'function') {
                window.history.pushState = function () {
                    var res = origPushState.apply(this, arguments);
                    try { onRouteWillChange(); } catch (e) {}
                    return res;
                };
            }

            var origReplaceState = window.history.replaceState;
            if (typeof origReplaceState === 'function') {
                window.history.replaceState = function () {
                    var res = origReplaceState.apply(this, arguments);
                    try { onRouteWillChange(); } catch (e) {}
                    return res;
                };
            }

            window.addEventListener('popstate', function () {
                try { onRouteWillChange(); } catch (e) {}
            });
        }

        scanAndReplaceAll();
    }

    window.licenseButtonHandler = {
        scan: scanAndReplaceAll,
        ensureSetup: setupForCurrentDocument
    };

    setupForCurrentDocument();
    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', setupForCurrentDocument, { once: true });
    }
})();