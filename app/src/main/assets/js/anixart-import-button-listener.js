/**
 * Инъекция кнопки "Импорт из Anixart" ниже кнопки "Скачанное" в меню сайта.
 */
(function() {
    function injectAnixartImportMenuItem() {
        if (document.getElementById('app-anixart-import-item')) {
            return;
        }

        var downloadedEl = document.getElementById('app-downloaded-anime-item');
        var anchorEl = downloadedEl;

        if (!anchorEl) {
            var menuItems = document.querySelectorAll('.menu-item');
            for (var i = 0; i < menuItems.length; i++) {
                var item = menuItems[i];
                var href = item.getAttribute('href') || '';
                var text = (item.textContent || '').trim();

                if (href.indexOf('/downloads') !== -1 || text.indexOf('Загрузки') !== -1 || text.indexOf('Скачанное') !== -1) {
                    anchorEl = item;
                    break;
                }
            }
        }

        if (!anchorEl) {
            return;
        }

        var importItem = anchorEl.cloneNode(true);
        importItem.id = 'app-anixart-import-item';
        importItem.setAttribute('href', 'javascript:void(0);');

        var textEl = importItem.querySelector('.menu-item__text');
        if (textEl) {
            textEl.textContent = 'Импорт из Anixart';
        } else {
            importItem.textContent = 'Импорт из Anixart';
        }

        // Заменим иконку если внутри SVG
        var svgEl = importItem.querySelector('svg');
        if (svgEl) {
            svgEl.innerHTML = '<path fill="currentColor" d="M9 16h6v-6h4l-7-7-7 7h4v6zm-4 2h14v2H5v-2z"/>';
        }

        importItem.addEventListener('click', function(e) {
            e.preventDefault();
            e.stopPropagation();
            if (e.stopImmediatePropagation) {
                e.stopImmediatePropagation();
            }
            if (window.AndroidInterface && window.AndroidInterface.onAnixartImportButtonClicked) {
                console.log('[AnimeLIB] "Импорт из Anixart" menu item clicked');
                window.AndroidInterface.onAnixartImportButtonClicked();
            } else {
                console.error('[AnimeLIB] AndroidInterface.onAnixartImportButtonClicked not found');
            }
        }, true);

        if (anchorEl.parentNode) {
            anchorEl.parentNode.insertBefore(importItem, anchorEl.nextSibling);
            console.log('[AnimeLIB] Successfully injected "Импорт из Anixart" menu item below "Скачанное"');
        }
    }

    injectAnixartImportMenuItem();

    if (!window.animelibAnixartImportObserverSetup) {
        window.animelibAnixartImportObserverSetup = true;

        var rafPending = false;
        var scheduleInject = function() {
            if (rafPending || document.getElementById('app-anixart-import-item')) return;
            rafPending = true;
            requestAnimationFrame(function() {
                rafPending = false;
                injectAnixartImportMenuItem();
            });
        };

        var observer = new MutationObserver(function(mutations) {
            if (document.getElementById('app-anixart-import-item')) return;
            for (var i = 0; i < mutations.length; i++) {
                var added = mutations[i].addedNodes;
                for (var j = 0; j < added.length; j++) {
                    if (added[j] && added[j].nodeType === 1) {
                        scheduleInject();
                        return;
                    }
                }
            }
        });

        var attach = function() {
            if (document.body) {
                observer.observe(document.body, { childList: true, subtree: true });
            }
        };

        if (document.body) {
            attach();
        } else {
            document.addEventListener('DOMContentLoaded', attach, { once: true });
        }
    }
    return 'anixart_import_button_setup_ok';
})();
