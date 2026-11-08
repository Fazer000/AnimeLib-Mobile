/**
 * Инъекция кнопки "Импорт из Anixart" ниже кнопки "Скачанное" в меню сайта.
 * Сохраняет оригинальную структуру пункта меню и иконку слева, аналогично пункту "Скачанное".
 */
(function() {
    function injectAnixartImportMenuItem() {
        var existing = document.getElementById('app-anixart-import-item');
        if (existing) {
            // Если иконка присутствует, повторно не создаем
            var hasIcon = existing.querySelector('svg, img, i, [class*="icon"]');
            if (hasIcon) {
                return;
            }
            // Если иконки нет (старая версия), удаляем для пересоздания в правильном виде
            if (existing.parentNode) {
                existing.parentNode.removeChild(existing);
            }
        }

        var downloadedEl = document.getElementById('app-downloaded-anime-item');
        var targetToClone = downloadedEl;

        if (!targetToClone) {
            var menuItems = document.querySelectorAll('.menu-item');
            for (var i = 0; i < menuItems.length; i++) {
                var item = menuItems[i];
                var href = item.getAttribute('href') || '';
                var text = (item.textContent || '').trim();

                if (href.indexOf('/downloads') !== -1 || text.indexOf('Загрузки') !== -1 || text.indexOf('Скачанное') !== -1) {
                    targetToClone = item;
                    break;
                }
            }
        }

        if (!targetToClone) {
            return;
        }

        // Клонируем в точности так же, как пункт "Скачанное", полностью сохраняя иконку слева
        var importItem = targetToClone.cloneNode(true);
        importItem.id = 'app-anixart-import-item';
        importItem.setAttribute('href', 'javascript:void(0);');

        // Меняем только текст, не затрагивая элементы иконки
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

        var insertAfter = downloadedEl || targetToClone;
        if (insertAfter.parentNode) {
            insertAfter.parentNode.insertBefore(importItem, insertAfter.nextSibling);
            console.log('[AnimeLIB] Successfully injected "Импорт из Anixart" menu item with icon in the same style as "Скачанное"');
        }
    }

    injectAnixartImportMenuItem();

    if (!window.animelibAnixartImportObserverSetup) {
        window.animelibAnixartImportObserverSetup = true;

        var rafPending = false;
        var scheduleInject = function() {
            if (rafPending) return;
            var cur = document.getElementById('app-anixart-import-item');
            if (cur && cur.querySelector('svg, img, i, [class*="icon"]')) {
                return;
            }
            rafPending = true;
            requestAnimationFrame(function() {
                rafPending = false;
                injectAnixartImportMenuItem();
            });
        };

        var observer = new MutationObserver(function(mutations) {
            var cur = document.getElementById('app-anixart-import-item');
            if (cur && cur.querySelector('svg, img, i, [class*="icon"]')) {
                return;
            }
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
