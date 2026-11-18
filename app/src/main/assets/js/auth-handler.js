/**
 * Обработчик перехвата токена, ID пользователя и клика кнопки «Выход из аккаунта»
 */

(function() {
    console.log('Auth handler loading...');

    // 1. Перехват сетевых запросов (/api/auth/oauth/token и /api/auth/me)
    if (!window.__authInterceptorInjected) {
        window.__authInterceptorInjected = true;

        // Перехват fetch API
        const originalFetch = window.fetch;
        if (typeof originalFetch === 'function') {
            window.fetch = async function(...args) {
                const response = await originalFetch.apply(this, args);
                try {
                    const url = (typeof args[0] === 'string') ? args[0] : (args[0] && args[0].url ? args[0].url : '');
                    if (url) {
                        if (url.includes('auth/oauth/token')) {
                            console.log('Intercepted fetch: /api/auth/oauth/token');
                            const clone = response.clone();
                            clone.text().then(text => {
                                if (window.AndroidInterface && typeof window.AndroidInterface.saveOAuthToken === 'function') {
                                    window.AndroidInterface.saveOAuthToken(text);
                                }
                            }).catch(e => console.error('Error reading oauth token response:', e));
                        } else if (url.includes('auth/me')) {
                            console.log('Intercepted fetch: /api/auth/me');
                            const clone = response.clone();
                            clone.text().then(text => {
                                if (window.AndroidInterface && typeof window.AndroidInterface.saveAuthMeData === 'function') {
                                    window.AndroidInterface.saveAuthMeData(text);
                                }
                            }).catch(e => console.error('Error reading auth me response:', e));
                        } else if (url.includes('auth/logout') || url.includes('/logout')) {
                            console.log('Intercepted fetch: logout endpoint');
                            if (window.AndroidInterface && typeof window.AndroidInterface.clearAuthToken === 'function') {
                                window.AndroidInterface.clearAuthToken();
                            }
                        }
                    }
                } catch (e) {
                    console.error('Fetch intercept error:', e);
                }
                return response;
            };
        }

        // Перехват XMLHttpRequest
        const originalXhrOpen = XMLHttpRequest.prototype.open;
        const originalXhrSend = XMLHttpRequest.prototype.send;

        XMLHttpRequest.prototype.open = function(method, url) {
            this.__requestUrl = url;
            return originalXhrOpen.apply(this, arguments);
        };

        XMLHttpRequest.prototype.send = function() {
            this.addEventListener('load', function() {
                try {
                    const url = this.__requestUrl || '';
                    if (url) {
                        if (url.includes('auth/oauth/token')) {
                            console.log('Intercepted XHR: /api/auth/oauth/token');
                            if (window.AndroidInterface && typeof window.AndroidInterface.saveOAuthToken === 'function') {
                                window.AndroidInterface.saveOAuthToken(this.responseText);
                            }
                        } else if (url.includes('auth/me')) {
                            console.log('Intercepted XHR: /api/auth/me');
                            if (window.AndroidInterface && typeof window.AndroidInterface.saveAuthMeData === 'function') {
                                window.AndroidInterface.saveAuthMeData(this.responseText);
                            }
                        } else if (url.includes('auth/logout') || url.includes('/logout')) {
                            console.log('Intercepted XHR: logout endpoint');
                            if (window.AndroidInterface && typeof window.AndroidInterface.clearAuthToken === 'function') {
                                window.AndroidInterface.clearAuthToken();
                            }
                        }
                    }
                } catch (e) {
                    console.error('XHR intercept error:', e);
                }
            });
            return originalXhrSend.apply(this, arguments);
        };
        console.log('Auth request interceptors injected successfully');
    }

    // 2. Перехват клика по кнопке «Выход из аккаунта»
    if (!window.__logoutListenerInjected) {
        window.__logoutListenerInjected = true;

        document.addEventListener('click', function(e) {
            let target = e.target;
            while (target && target !== document.body && target !== document.documentElement) {
                if (target.tagName === 'BUTTON' || target.tagName === 'A' || (target.classList && target.classList.contains('btn'))) {
                    const text = (target.innerText || target.textContent || '').trim();
                    const hasLogoutText = text.includes('Выход из аккаунта') || text.includes('Выход');
                    const hasLogoutIcon = target.querySelector('.fa-right-from-bracket') !== null ||
                                          target.querySelector('[data-icon="right-from-bracket"]') !== null ||
                                          (target.getAttribute && target.getAttribute('data-icon') === 'right-from-bracket');
                    const isDangerBtn = target.classList && target.classList.contains('variant-danger');

                    if (hasLogoutText || (isDangerBtn && (hasLogoutIcon || hasLogoutText))) {
                        console.log('Logout button clicked in WebView');
                        if (window.AndroidInterface && typeof window.AndroidInterface.clearAuthToken === 'function') {
                            window.AndroidInterface.clearAuthToken();
                        }
                    }
                    break;
                }
                target = target.parentElement;
            }
        }, true);
        console.log('Logout click listener attached');
    }

    // 3. Дополнительная синхронизация из localStorage и document.cookie при наличии
    if (typeof localStorage !== 'undefined' && !window.__setItemIntercepted) {
        window.__setItemIntercepted = true;
        try {
            const originalSetItem = localStorage.setItem;
            localStorage.setItem = function(key, val) {
                originalSetItem.apply(this, arguments);
                if (key === 'auth' && val && val !== 'null' && val !== 'undefined') {
                    if (window.AndroidInterface && typeof window.AndroidInterface.saveAuthLocalStorage === 'function') {
                        window.AndroidInterface.saveAuthLocalStorage(val);
                    }
                } else if (key === 'latest-views' && val && val !== 'null' && val !== 'undefined') {
                    const host = (window.location && window.location.hostname) ? window.location.hostname.toLowerCase() : '';
                    if (host.indexOf('animelib') !== -1) {
                        if (window.AndroidInterface && typeof window.AndroidInterface.saveLatestViews === 'function') {
                            window.AndroidInterface.saveLatestViews(val);
                        }
                    }
                }
            };

            const originalRemoveItem = localStorage.removeItem;
            localStorage.removeItem = function(key) {
                originalRemoveItem.apply(this, arguments);
                if (key === 'latest-views') {
                    const host = (window.location && window.location.hostname) ? window.location.hostname.toLowerCase() : '';
                    if (host.indexOf('animelib') !== -1) {
                        if (window.AndroidInterface && typeof window.AndroidInterface.saveLatestViews === 'function') {
                            window.AndroidInterface.saveLatestViews('[]');
                        }
                    }
                }
            };
        } catch (e) {
            console.error('Error overriding localStorage.setItem:', e);
        }
    }

    function syncAuthToken() {
        try {
            if (typeof localStorage !== 'undefined') {
                const auth = localStorage.getItem('auth');
                if (auth && auth !== 'null' && auth !== 'undefined') {
                    if (window.AndroidInterface && typeof window.AndroidInterface.saveAuthLocalStorage === 'function') {
                        window.AndroidInterface.saveAuthLocalStorage(auth);
                    }
                    if (window.AndroidInterface && typeof window.AndroidInterface.getAuthFromLocalStorage === 'function') {
                        window.AndroidInterface.getAuthFromLocalStorage();
                    }
                } else if (window.AndroidInterface && typeof window.AndroidInterface.getSavedAuthJson === 'function') {
                    const savedAuth = window.AndroidInterface.getSavedAuthJson();
                    if (savedAuth && savedAuth !== 'null' && savedAuth !== 'undefined') {
                        localStorage.setItem('auth', savedAuth);
                        console.log('[auth-handler] Restored auth from Android DB to localStorage for ' + window.location.hostname);
                    }
                }

                const host = (window.location && window.location.hostname) ? window.location.hostname.toLowerCase() : '';
                if (host.indexOf('animelib') !== -1 && window.AndroidInterface && typeof window.AndroidInterface.getLatestViewsJson === 'function') {
                    const savedLatest = window.AndroidInterface.getLatestViewsJson();
                    if (savedLatest && savedLatest !== '[]' && savedLatest !== 'null') {
                        try {
                            var newViews = JSON.parse(savedLatest);
                            var existingStr = localStorage.getItem('latest-views');
                            var list = [];
                            if (existingStr) {
                                try { list = JSON.parse(existingStr); } catch(e) {}
                            }
                            if (!Array.isArray(list)) list = [];
                            function isPlaceholderTitle(s) {
                                if (!s || typeof s !== 'string') return true;
                                var t = s.trim();
                                if (!t || t === '-' || t === '—') return true;
                                return /^(anime|аниме|загрузка\.{0,3}|аниме\s*#\d+|anime\s*#\d+)$/i.test(t);
                            }
                            function hasValidCover(c) {
                                if (!c || typeof c !== 'object') return false;
                                var vals = [c.default, c.md, c.thumbnail, c.filename];
                                for (var i = 0; i < vals.length; i++) {
                                    var v = vals[i];
                                    if (typeof v === 'string') {
                                        v = v.trim();
                                        var low = v.toLowerCase();
                                        if (v && low.indexOf('file://') !== 0 && low.indexOf('content://') !== 0 &&
                                            low.indexOf('/data/') !== 0 && low.indexOf('/storage/') !== 0 &&
                                            low.indexOf('/sdcard/') !== 0 && low.indexOf('placeholder') === -1) return true;
                                    }
                                }
                                return false;
                            }
                            function extractNumericId(m) {
                                if (!m) return 0;
                                if (m.id && parseInt(m.id, 10) > 0) return parseInt(m.id, 10);
                                var s = m.slug_url || m.slug || '';
                                var match = String(s).match(/^(\d+)/);
                                return match ? parseInt(match[1], 10) : 0;
                            }
                            function isSameMedia(a, b) {
                                if (!a || !b) return false;
                                var idA = extractNumericId(a), idB = extractNumericId(b);
                                if (idA > 0 && idB > 0 && idA === idB) return true;
                                var suA = (a.slug_url || '').toLowerCase(), suB = (b.slug_url || '').toLowerCase();
                                if (suA && suB && suA !== 'anime' && suB !== 'anime' && suA === suB) return true;
                                var sA = (a.slug || '').toLowerCase(), sB = (b.slug || '').toLowerCase();
                                if (sA && sB && sA !== 'anime' && sB !== 'anime' && !/^\d+$/.test(sA) && sA === sB) return true;
                                return false;
                            }
                            function mergeMedia(incoming, existing) {
                                if (!existing) return incoming;
                                var out = Object.assign({}, existing, incoming);
                                var incCov = hasValidCover(incoming.cover);
                                var exCov = hasValidCover(existing.cover);
                                if (!incCov && exCov) {
                                    out.cover = existing.cover;
                                } else if (incCov && exCov) {
                                    out.cover = Object.assign({}, existing.cover, incoming.cover);
                                }
                                var incId = (incoming.id && parseInt(incoming.id, 10) > 0) ? parseInt(incoming.id, 10) : 0;
                                var exId = (existing.id && parseInt(existing.id, 10) > 0) ? parseInt(existing.id, 10) : 0;
                                out.id = incId > 0 ? incId : (exId > 0 ? exId : extractNumericId(out));
                                var incSu = incoming.slug_url || '', exSu = existing.slug_url || '';
                                if (incSu.indexOf('--') === -1 && exSu.indexOf('--') !== -1) {
                                    out.slug_url = exSu;
                                } else if ((!incSu || incSu === 'anime') && exSu) {
                                    out.slug_url = exSu;
                                }
                                var fSu = out.slug_url || '';
                                if (fSu.indexOf('--') !== -1) {
                                    out.slug = fSu.split('--').slice(1).join('--') || existing.slug || incoming.slug || '';
                                } else if ((!incoming.slug || incoming.slug === 'anime' || /^\d+$/.test(incoming.slug)) &&
                                           existing.slug && existing.slug !== 'anime' && !/^\d+$/.test(existing.slug)) {
                                    out.slug = existing.slug;
                                }
                                ['name', 'rus_name', 'eng_name'].forEach(function(k) {
                                    var incV = incoming[k] || '', exV = existing[k] || '';
                                    if (exV && !isPlaceholderTitle(exV)) {
                                        if (isPlaceholderTitle(incV) || (!incCov && exCov)) {
                                            out[k] = exV;
                                        }
                                    } else if (isPlaceholderTitle(incV)) {
                                        out[k] = '';
                                    }
                                });
                                if (!out.site) out.site = 5;
                                if (!out.model) out.model = 'anime';
                                return out;
                            }
                            var updated = false;
                            if (Array.isArray(newViews)) {
                                for (var k = newViews.length - 1; k >= 0; k--) {
                                    var newItem = newViews[k];
                                    if (!newItem || !newItem.media) continue;
                                    var idx = -1;
                                    for (var i = 0; i < list.length; i++) {
                                        if (list[i] && list[i].media && isSameMedia(newItem.media, list[i].media)) {
                                            idx = i;
                                            break;
                                        }
                                    }
                                    if (idx !== -1) {
                                        var mergedEntry = Object.assign({}, list[idx], newItem);
                                        mergedEntry.media = mergeMedia(newItem.media, list[idx].media);
                                        if (mergedEntry.item && list[idx].item && !(mergedEntry.item.id > 0) &&
                                            list[idx].item.id > 0 && mergedEntry.item.number === list[idx].item.number) {
                                            mergedEntry.item.id = list[idx].item.id;
                                            if (mergedEntry.meta && !(mergedEntry.meta.episode > 0)) {
                                                mergedEntry.meta.episode = list[idx].item.id;
                                            }
                                        }
                                        list[idx] = mergedEntry;
                                        updated = true;
                                    } else {
                                        var cleanItem = Object.assign({}, newItem);
                                        cleanItem.media = mergeMedia(newItem.media, null);
                                        list.unshift(cleanItem);
                                        updated = true;
                                    }
                                }
                            }
                            if (updated) {
                                if (list.length > 50) list = list.slice(0, 50);
                                localStorage.setItem('latest-views', JSON.stringify(list));
                                console.log('[auth-handler] Restored/updated latest-views in localStorage');
                            }
                        } catch(e) {
                            console.error('[auth-handler] Error syncing latest-views:', e);
                        }
                    }
                }
            }
        } catch (error) {
            console.error('Error checking localStorage:', error);
        }

        try {
            if (document.cookie && window.CookieManager && typeof window.CookieManager.setCookie === 'function') {
                const cookies = document.cookie.split(';');
                cookies.forEach(c => {
                    const parts = c.trim().split('=');
                    if (parts.length >= 2) {
                        const name = parts[0].trim();
                        const val = parts.slice(1).join('=').trim();
                        if (name) {
                            window.CookieManager.setCookie(name, val, window.location.hostname);
                        }
                    }
                });
            }
        } catch (e) {
            console.error('Error syncing document.cookie:', e);
        }
    }
    syncAuthToken();
})();
'auth_handler_ok';

