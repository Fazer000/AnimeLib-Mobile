(function() {
    if (window.animelibSiteLogoLongPressSetup) return;
    window.animelibSiteLogoLongPressSetup = true;

    console.log('[AnimeLIB] Setting up site logo long-press handler');

    // Clean up native sites-list popup, overlays, and body locks
    window.animelibKillSitesPopup = function() {
        try {
            var popups = document.querySelectorAll('[data-name="sites-list"], .popup[data-name="sites-list"]');
            popups.forEach(function(popup) {
                var closeBtn = popup.querySelector('.popup-close');
                if (closeBtn) {
                    try { closeBtn.click(); } catch(e) {}
                }
                var root = popup.closest('.popup-root, .popup-wrapper');
                if (root) {
                    root.remove();
                } else {
                    popup.remove();
                }
            });
        } catch(e) {}

        try {
            var overlays = document.querySelectorAll('.popup-overlay');
            overlays.forEach(function(ov) {
                if (!ov.closest('.popup') || ov.closest('[data-name="sites-list"]')) {
                    ov.remove();
                }
            });
        } catch(e) {}

        try {
            document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', code: 'Escape', keyCode: 27, bubbles: true }));
        } catch(e) {}

        try {
            if (document.body) {
                document.body.style.removeProperty('overflow');
                document.body.style.removeProperty('position');
                document.body.style.removeProperty('pointer-events');
                document.body.style.removeProperty('touch-action');
                document.body.classList.remove('is-hidden', 'is-locked', 'popup-open', 'modal-open', 'noscroll', 'overflow-hidden');
            }
            if (document.documentElement) {
                document.documentElement.style.removeProperty('overflow');
                document.documentElement.style.removeProperty('position');
                document.documentElement.style.removeProperty('pointer-events');
                document.documentElement.classList.remove('is-locked', 'popup-open', 'modal-open', 'noscroll', 'overflow-hidden');
            }
        } catch(e) {}
    };

    // 1. Inject CSS rule to hide site's native sites-list popup and its root container
    function injectHideStyle() {
        var styleId = 'animelib-prevent-native-sites-popup';
        if (!document.getElementById(styleId)) {
            var hideStyle = document.createElement('style');
            hideStyle.id = styleId;
            hideStyle.innerHTML = `
                .popup-root:has([data-name="sites-list"]),
                [data-name="sites-list"],
                .popup[data-name="sites-list"] {
                    display: none !important;
                    visibility: hidden !important;
                    opacity: 0 !important;
                    pointer-events: none !important;
                    width: 0 !important;
                    height: 0 !important;
                    z-index: -9999 !important;
                }
            `;
            if (document.head || document.documentElement) {
                (document.head || document.documentElement).appendChild(hideStyle);
            }
        }
    }
    injectHideStyle();
    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', injectHideStyle);
    }

    // 2. Observer to auto-close and clean up any native sites-list popup created by site JS
    try {
        var observer = new MutationObserver(function() {
            if (document.querySelector('[data-name="sites-list"]')) {
                window.animelibKillSitesPopup();
            }
        });
        observer.observe(document.body || document.documentElement, {
            childList: true,
            subtree: true,
            attributes: true,
            attributeFilter: ['class', 'style']
        });
    } catch(e) {}

    window.animelibKillSitesPopup();

    var longPressTimer = null;
    var isLongPressed = false;
    var suppressNextClickEl = null;
    var suppressNextClickUntil = 0;
    var startX = 0;
    var startY = 0;
    var LONG_PRESS_DURATION = 300; // ms
    var activeLogoEl = null;

    function getTargetLogoAnchor(target) {
        if (!target || target.nodeType !== 1) return null;

        var homeItem = target.closest('[data-bottom-menu-name="home"], [data-nav="home"]');
        if (homeItem) return homeItem;

        var logoEl = target.closest('.site-logo, .header__logo, .header-logo, [class*="site-logo"], [class*="header__logo"]');
        if (logoEl) {
            var anchor = logoEl.closest('a');
            return anchor || logoEl;
        }

        var vpAnchor = target.closest('a.vp_j');
        if (vpAnchor && vpAnchor.querySelector('.site-logo, [class*="logo"], svg, img')) {
            return vpAnchor;
        }

        var anchor = target.closest('a');
        if (anchor) {
            var href = anchor.getAttribute('href');
            if (href === '/' || href === '/ru' || href === '/ru/' || (href && (href.startsWith('/ru?') || href.startsWith('/?')))) {
                var isHeaderOrNav = anchor.closest('header, nav, .header, .bottom-menu, .top-bar, #header');
                var hasLogoVisual = anchor.querySelector('svg, img, [class*="logo"], [class*="icon"]');
                var isHomeAttr = anchor.getAttribute('data-bottom-menu-name') === 'home';
                if (isHeaderOrNav || hasLogoVisual || isHomeAttr || anchor.classList.contains('vp_j')) {
                    return anchor;
                }
            }
        }

        return null;
    }

    function cancelTimer() {
        if (longPressTimer) {
            clearTimeout(longPressTimer);
            longPressTimer = null;
        }
    }

    function blockEvent(e) {
        if (e.cancelable) e.preventDefault();
        e.stopPropagation();
        if (e.stopImmediatePropagation) e.stopImmediatePropagation();
        return false;
    }

    function handleStart(e) {
        var logoEl = getTargetLogoAnchor(e.target);
        if (!logoEl) return;

        cancelTimer();
        isLongPressed = false;
        activeLogoEl = logoEl;

        var touch = e.touches ? e.touches[0] : e;
        startX = touch.clientX;
        startY = touch.clientY;

        longPressTimer = setTimeout(function() {
            isLongPressed = true;
            suppressNextClickEl = activeLogoEl;
            suppressNextClickUntil = Date.now() + 1500;
            console.log('[AnimeLIB] Site logo long press triggered!');

            window.animelibKillSitesPopup();

            try {
                if (navigator.vibrate) navigator.vibrate(40);
            } catch(err) {}

            if (window.AndroidInterface && window.AndroidInterface.onSiteLogoLongPressed) {
                window.AndroidInterface.onSiteLogoLongPressed();
            }

            cancelTimer();
        }, LONG_PRESS_DURATION);
    }

    function handleMove(e) {
        if (!longPressTimer) return;
        var touch = e.touches ? e.touches[0] : e;
        var moveX = Math.abs(touch.clientX - startX);
        var moveY = Math.abs(touch.clientY - startY);
        if (moveX > 10 || moveY > 10) {
            cancelTimer();
            isLongPressed = false;
        }
    }

    function handleEnd(e) {
        cancelTimer();
        if (isLongPressed) {
            isLongPressed = false;
            window.animelibKillSitesPopup();
            return blockEvent(e);
        }
    }

    function handleIntercept(e) {
        if (e.type === 'contextmenu') {
            var logoEl = getTargetLogoAnchor(e.target);
            if (logoEl) {
                window.animelibKillSitesPopup();
                return blockEvent(e);
            }
        }

        if (e.type === 'click' || e.type === 'auxclick') {
            if (suppressNextClickEl && Date.now() < suppressNextClickUntil) {
                var clickedLogo = getTargetLogoAnchor(e.target);
                if (clickedLogo && (clickedLogo === suppressNextClickEl || suppressNextClickEl.contains(e.target) || e.target.contains(suppressNextClickEl))) {
                    suppressNextClickEl = null;
                    window.animelibKillSitesPopup();
                    return blockEvent(e);
                }
            }
        }
    }

    ['touchstart', 'mousedown', 'pointerdown'].forEach(function(evt) {
        document.addEventListener(evt, handleStart, { passive: false, capture: true });
    });

    ['touchmove', 'mousemove', 'pointermove'].forEach(function(evt) {
        document.addEventListener(evt, handleMove, { passive: true, capture: true });
    });

    ['touchend', 'touchcancel', 'mouseup', 'pointerup', 'pointercancel'].forEach(function(evt) {
        document.addEventListener(evt, handleEnd, { passive: false, capture: true });
    });

    ['click', 'contextmenu', 'auxclick'].forEach(function(evt) {
        document.addEventListener(evt, handleIntercept, { passive: false, capture: true });
        window.addEventListener(evt, handleIntercept, { passive: false, capture: true });
    });

    console.log('[AnimeLIB] Site logo long-press listeners successfully installed');
})();
