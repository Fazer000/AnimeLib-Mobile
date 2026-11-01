/**
 * Перехватывает кнопки поиска сайта и передаёт клик в Android.
 */
try {
    if (window.animelibSearchSetup) {
        console.log('[AnimeLIB Search] Listener already setup');
    } else {
        window.animelibSearchSetup = true;

        var SEARCH_ICON = '.fa-magnifying-glass, [data-icon="magnifying-glass"]';
        var BACK_ICON = '.fa-arrow-left, [data-icon="arrow-left"], .fa-chevron-left, [data-icon="chevron-left"], .fa-xmark, [data-icon="xmark"]';
        var LABELS = ['быстрый поиск', 'поиск'];
        var MAX_DEPTH = 4;

        /** Возвращает нормализованный текст элемента. */
        function normalizeText(el) {
            return (el.textContent || '').replace(/\s+/g, ' ').trim().toLowerCase();
        }

        /** Проверяет, что элемент сам является кнопкой поиска, а не её контейнером. */
        function isSearchButton(el) {
            if (!el || el.nodeType !== 1 || el === document.body || !el.querySelector) {
                return false;
            }
            if (el.querySelector('input, textarea')) {
                return false;
            }
            if (!el.querySelector(SEARCH_ICON)) {
                return false;
            }

            var text = normalizeText(el);
            if (text === '') {
                return !!(el.classList && el.classList.contains('cm_ct'));
            }
            return LABELS.indexOf(text) !== -1;
        }

        /** Определяет клик по кнопке «назад» / «закрыть». */
        function isBackControl(target) {
            var el = target;
            for (var i = 0; i < 3 && el; i++) {
                if (el.matches && el.matches(BACK_ICON)) {
                    return true;
                }
                if ((el.tagName === 'BUTTON' || el.tagName === 'A') && el.querySelector && el.querySelector(BACK_ICON)) {
                    return true;
                }
                el = el.parentElement;
            }
            return false;
        }

        /** Ищет кнопку поиска среди цели события и её ближайших родителей. */
        function findSearchButton(target) {
            if (isBackControl(target)) {
                return null;
            }
            var el = target;
            for (var i = 0; i < MAX_DEPTH && el && el !== document.body; i++) {
                if (isSearchButton(el)) {
                    return el;
                }
                el = el.parentElement;
            }
            return null;
        }

        /** Гасит событие сайта и открывает нативный поиск. */
        function handleEvent(e) {
            var target = e.target;
            if (!target || target.tagName === 'INPUT' || target.tagName === 'TEXTAREA') {
                return;
            }
            if (!findSearchButton(target)) {
                return;
            }

            e.stopPropagation();
            if (e.stopImmediatePropagation) {
                e.stopImmediatePropagation();
            }
            if (e.type !== 'click') {
                return;
            }
            e.preventDefault();

            if (window.AndroidInterface && window.AndroidInterface.onSearchButtonClicked) {
                console.log('[AnimeLIB Search] Search button clicked');
                window.AndroidInterface.onSearchButtonClicked();
            } else {
                console.error('[AnimeLIB Search] AndroidInterface not found');
            }
        }

        ['pointerdown', 'mousedown', 'touchstart'].forEach(function (type) {
            document.addEventListener(type, handleEvent, { passive: true, capture: true });
        });
        document.addEventListener('click', handleEvent, { passive: false, capture: true });

        console.log('[AnimeLIB Search] Listener setup complete');
    }
    'search_setup_ok';
} catch (e) {
    console.error('[AnimeLIB Search] Error: ' + e.message);
    'search_error: ' + e.message;
}