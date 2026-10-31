// Слушатель для кнопок плеера (ссылки /watch, /episode)
try {
  console.log('[AnimeLIB] Starting player button listener');

  function isAnimelibDomain(href) {
    var host = (window.location && window.location.hostname) ? window.location.hostname.toLowerCase() : '';
    if (host.indexOf('animelib') !== -1) return true;
    if (href && typeof href === 'string' && href.toLowerCase().indexOf('animelib') !== -1) return true;
    return false;
  }

  var EXCLUDED_PLAYER_INTERCEPT_SELECTOR = '.wh_w, svg.fa-trash-can, .fa-trash-can, [data-icon="trash-can"]';

  function isExcludedFromPlayerInterception(target) {
    if (!target) return false;
    var startEl = target.nodeType === 1 ? target : target.parentElement;
    if (!startEl) return false;

    if (typeof startEl.closest === 'function' && startEl.closest(EXCLUDED_PLAYER_INTERCEPT_SELECTOR)) {
      return true;
    }

    var node = startEl;
    for (var i = 0; i < 4 && node && node !== document.body; i++) {
      var tag = (node.tagName || '').toUpperCase();
      if (tag === 'A') break;
      if (node.classList && node.classList.contains('wh_w')) {
        return true;
      }
      if (node.getAttribute && node.getAttribute('data-icon') === 'trash-can') {
        return true;
      }
      if (node.children && node.children.length === 1) {
        var onlyChild = node.children[0];
        if (onlyChild && (
          (onlyChild.getAttribute && onlyChild.getAttribute('data-icon') === 'trash-can') ||
          (onlyChild.classList && onlyChild.classList.contains('fa-trash-can'))
        )) {
          return true;
        }
      }
      node = node.parentElement;
    }

    return false;
  }

  function syncLatestViewsAfterVanillaAction() {
    try {
      if (!isAnimelibDomain('')) return;
      if (typeof localStorage === 'undefined') return;
      var before = localStorage.getItem('latest-views');
      setTimeout(function () {
        try {
          var after = localStorage.getItem('latest-views');
          if (after !== before && window.AndroidInterface && typeof window.AndroidInterface.saveLatestViews === 'function') {
            window.AndroidInterface.saveLatestViews(after || '[]');
            console.log('[AnimeLIB] Synced updated latest-views after vanilla delete action');
          }
        } catch (err) {
          console.warn('[AnimeLIB] Failed to sync latest-views after delete: ' + err.message);
        }
      }, 150);
    } catch (err) {
      // ignore
    }
  }

  if (window.animelibPlayerSetup) {
    console.log('[AnimeLIB] Player listener already setup');
  } else {
    window.animelibPlayerSetup = true;

    document.addEventListener('click', function (e) {
      if (isExcludedFromPlayerInterception(e.target)) {
        console.log('[AnimeLIB] Skipping player click interception for excluded element (.wh_w / trash-can)');
        syncLatestViewsAfterVanillaAction();
        return;
      }

      var startTarget = (e.target && e.target.nodeType === 1) ? e.target : (e.target ? e.target.parentElement : null);
      console.log('[AnimeLIB] Player click detected on: ' + (startTarget ? startTarget.tagName : 'unknown'));

      let el = startTarget;
      for (let i = 0; i < 5 && el; i++) {
        if (el.tagName && el.tagName.toUpperCase() === 'A') {
          let href = el.href || el.getAttribute('href') || '';
          console.log('[AnimeLIB] Player link found: ' + href);

          if (href.includes('/watch') || href.includes('episode')) {
            if (!isAnimelibDomain(href)) {
              console.log('[AnimeLIB] Skipping player button click interception for non-animelib domain');
              return;
            }
            console.log('[AnimeLIB] Player button clicked: ' + href);
            e.preventDefault();
            e.stopPropagation();
            AndroidInterface.onPlayerButtonClicked(href);
            break;
          }
        }
        el = el.parentElement;
      }
    }, true);

    console.log('[AnimeLIB] Player listener setup complete');
  }
  'player_setup_ok';
} catch (e) {
  console.error('[AnimeLIB] Player listener error: ' + e.message);
  'player_error: ' + e.message;
}
