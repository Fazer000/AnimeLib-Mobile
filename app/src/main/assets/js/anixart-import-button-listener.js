/**
 * Инъекция кнопки "Импорт из Anixart" ниже кнопки "Скачанное" в меню сайта.
 * Содержит собственный векторный значок импорта списка в стиле иконок сайта.
 */
(function() {
    function injectAnixartImportMenuItem() {
        var existing = document.getElementById('app-anixart-import-item');
        if (existing) {
            // Если кнопка уже создана с новым значком, не пересоздаем
            if (existing.querySelector('[data-custom-import-icon="true"]')) {
                return;
            }
            if (existing.parentNode) {
                existing.parentNode.removeChild(existing);
            }
        }

        var downloadedEl = document.getElementById('app-downloaded-anime-item');
        var anchorEl = downloadedEl;

        if (!anchorEl) {
            var menuItems = document.querySelectorAll('.menu-item, [class*="menu"] a, nav a');
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
        importItem.style.display = '';

        // Меняем текст кнопки
        var textEl = importItem.querySelector('.menu-item__text');
        if (textEl) {
            textEl.textContent = 'Импорт из Anixart';
        } else {
            var replaced = false;
            for (var k = 0; k < importItem.childNodes.length; k++) {
                var node = importItem.childNodes[k];
                if (node.nodeType === 3 && node.textContent.trim().length > 0) {
                    node.textContent = ' Импорт из Anixart';
                    replaced = true;
                    break;
                }
            }
            if (!replaced) {
                var spans = importItem.querySelectorAll('span');
                for (var s = spans.length - 1; s >= 0; s--) {
                    if (spans[s].children.length === 0 && spans[s].textContent.trim().length > 0) {
                        spans[s].textContent = 'Импорт из Anixart';
                        replaced = true;
                        break;
                    }
                }
            }
        }

        // Устанавливаем новый векторный значок импорта списка (документ со стрелкой) в стиле иконок сайта
        var svgEl = importItem.querySelector('svg');
        if (svgEl) {
            svgEl.setAttribute('data-custom-import-icon', 'true');
            svgEl.innerHTML = '<path fill="currentColor" d="M14 2H6c-1.1 0-2 .9-2 2v16c0 1.1.9 2 2 2h12c1.1 0 2-.9 2-2V8l-6-6zm1 10h-2v3h-2v-3H9l3-3 3 3zm-2-4V3.5L18.5 9H13z"/>';
        } else {
            var iconContainer = importItem.querySelector('.menu-item__icon, [class*="icon"]');
            if (iconContainer) {
                iconContainer.setAttribute('data-custom-import-icon', 'true');
                iconContainer.innerHTML = '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" width="24" height="24" style="display:block;"><path fill="currentColor" d="M14 2H6c-1.1 0-2 .9-2 2v16c0 1.1.9 2 2 2h12c1.1 0 2-.9 2-2V8l-6-6zm1 10h-2v3h-2v-3H9l3-3 3 3zm-2-4V3.5L18.5 9H13z"/></svg>';
            }
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

        var insertAfter = downloadedEl || anchorEl;
        if (insertAfter.parentNode) {
            insertAfter.parentNode.insertBefore(importItem, insertAfter.nextSibling);
            console.log('[AnimeLIB] Successfully injected "Импорт из Anixart" menu item with new dedicated import icon');
        }
    }

    injectAnixartImportMenuItem();

    if (!window.animelibAnixartImportObserverSetup) {
        window.animelibAnixartImportObserverSetup = true;

        var rafPending = false;
        var scheduleInject = function() {
            if (rafPending) return;
            var cur = document.getElementById('app-anixart-import-item');
            if (cur && cur.querySelector('[data-custom-import-icon="true"]')) {
                return;
            }
            rafPending = true;
            requestAnimationFrame(function() {
                rafPending = false;
                injectAnixartImportMenuItem();
            });
        };

        var observer = new MutationObserver(function(mutations) {
            scheduleInject();
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
