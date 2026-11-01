// Замена оригинальной кнопки "Тема" на сайте кастомным элементом с прямым обработчиком клика
try {
  console.log('[AnimeLIB] Starting theme button replacer');

  var FORBIDDEN_WORDS = ['настройки', 'поиск', 'профиль', 'каталог', 'выход', 'форум', 'чатик', 'загрузки'];

  function isForbidden(el) {
    if (!el || el.nodeType !== 1) return true;
    var cl = typeof el.className === 'string' ? el.className.toLowerCase() : '';
    if (cl.includes('popup') || cl.includes('overlay') || cl.includes('scrollable') || 
        cl.includes('abr_abs') || cl.includes('abr_abu') || cl.includes('menu-list') || 
        cl.includes('collapse') || cl.includes('popup-root') || cl.includes('popup__inner') || cl.includes('popup__content')) {
      return true;
    }
    var text = (el.textContent || '').trim().toLowerCase();
    for (var i = 0; i < FORBIDDEN_WORDS.length; i++) {
      if (text.includes(FORBIDDEN_WORDS[i])) {
        return true;
      }
    }
    return false;
  }

  var MENU_SPAN_SELECTOR = '.abr_abv span, .menu-item span, .popup span, nav span, aside span, [class*="menu"] span';

  function replaceThemeButton(rootNode) {
    var scope = (rootNode && rootNode.querySelectorAll) ? rootNode : document;
    var spans = scope.querySelectorAll(MENU_SPAN_SELECTOR);
    for (var i = 0; i < spans.length; i++) {
      var span = spans[i];
      var text = (span.textContent || '').trim().toLowerCase();
      if (text === 'тема' || text === 'тема оформления') {
        // Находим конкретный элемент списка меню (<div class="abr_abv"> или близкий блок)
        var targetEl = span.closest('.abr_abv, .menu-item, button, a');
        if (!targetEl && span.parentElement) {
          targetEl = span.parentElement;
        }

        if (!targetEl) continue;

        if (targetEl.getAttribute('data-animelib-custom-theme') === 'true') {
          continue; // Уже заменен
        }

        // Защита: проверяем, что это не запрещенный контейнер и не содержит других пунктов
        if (isForbidden(targetEl)) {
          // Попробуем взять непосредственно сам span или его прямого узкого родителя
          if (span.parentElement && !isForbidden(span.parentElement) && span.parentElement.children.length <= 3) {
            targetEl = span.parentElement;
          } else {
            continue;
          }
        }

        var parent = targetEl.parentNode;
        if (!parent) continue;

        // Создаем точную кастомную копию элемента пункта "Тема"
        var customBtn = document.createElement(targetEl.tagName || 'div');
        customBtn.className = targetEl.className;
        customBtn.innerHTML = targetEl.innerHTML;
        customBtn.setAttribute('data-animelib-custom-theme', 'true');
        customBtn.style.cursor = 'pointer';

        // Обработчик клика только для кнопки "Тема"
        var handleCustomClick = function(e) {
          if (e) {
            e.preventDefault();
            e.stopPropagation();
            if (e.stopImmediatePropagation) {
              e.stopImmediatePropagation();
            }
          }
          console.log('[AnimeLIB] Custom theme button clicked!');
          if (window.AndroidInterface && window.AndroidInterface.onThemeButtonClicked) {
            window.AndroidInterface.onThemeButtonClicked();
          }
        };

        customBtn.addEventListener('click', handleCustomClick, true);
        customBtn.addEventListener('touchstart', function(e) { e.stopPropagation(); }, true);
        customBtn.onclick = handleCustomClick;

        try {
          parent.replaceChild(customBtn, targetEl);
          console.log('[AnimeLIB] Successfully replaced theme button node with custom node');
        } catch (err) {
          console.error('[AnimeLIB] Failed to replace theme button:', err);
        }
      }
    }
  }

  // Запускаем замену
  replaceThemeButton(document);

  if (!window.animelibThemeReplacerSetup) {
    window.animelibThemeReplacerSetup = true;

    var rafScheduled = false;
    var scheduleReplace = function() {
      if (rafScheduled) return;
      rafScheduled = true;
      requestAnimationFrame(function() {
        rafScheduled = false;
        replaceThemeButton(document);
      });
    };

    var observer = new MutationObserver(function(mutations) {
      for (var i = 0; i < mutations.length; i++) {
        var added = mutations[i].addedNodes;
        for (var j = 0; j < added.length; j++) {
          if (added[j] && added[j].nodeType === 1) {
            scheduleReplace();
            return;
          }
        }
      }
    });

    var attachObserver = function() {
      if (document.body) {
        observer.observe(document.body, { childList: true, subtree: true });
      }
    };

    if (document.body) {
      attachObserver();
    } else {
      document.addEventListener('DOMContentLoaded', attachObserver, { once: true });
    }
  }

  'theme_setup_ok';
} catch (e) {
  console.error('[AnimeLIB] Theme button listener error: ' + e.message);
  'theme_error: ' + e.message;
}





