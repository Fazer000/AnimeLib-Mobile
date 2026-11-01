/**
 * Обработчик для замены стандартных HTML select элементов на кастомные диалоги
 */

class CustomSelectHandler {
    constructor() {
        this.selectElements = new Map();
        this.observer = null;
        this.isInitialized = false;
    }

    /**
     * Инициализация обработчика
     */
    init() {
        if (this.isInitialized) {
            console.log('CustomSelectHandler already initialized');
            return;
        }

        console.log('Initializing CustomSelectHandler');
        
        // Обрабатываем существующие select элементы
        this.processExistingSelects();
        
        // Настраиваем наблюдатель за изменениями DOM
        this.setupMutationObserver();
        
        this.isInitialized = true;
        console.log('CustomSelectHandler initialized successfully');
    }

    /**
     * Обработка существующих select элементов на странице
     */
    processExistingSelects() {
        const selects = document.querySelectorAll('select');
        console.log('Found ' + selects.length + ' select elements');
        
        selects.forEach((select, index) => {
            this.replaceSelectElement(select, index);
        });
    }

    /**
     * Настройка MutationObserver для отслеживания новых select элементов
     */
    setupMutationObserver() {
        this.observer = new MutationObserver((mutations) => {
            mutations.forEach((mutation) => {
                mutation.addedNodes.forEach((node) => {
                    if (node.nodeType === Node.ELEMENT_NODE) {
                        // Проверяем сам узел
                        if (node.tagName === 'SELECT') {
                            this.replaceSelectElement(node);
                        }
                        
                        // Проверяем дочерние select элементы
                        const childSelects = node.querySelectorAll && node.querySelectorAll('select');
                        if (childSelects) {
                            childSelects.forEach((select) => {
                                this.replaceSelectElement(select);
                            });
                        }
                    }
                });
            });
        });

        const attach = () => {
            if (document.body && this.observer) {
                this.observer.observe(document.body, {
                    childList: true,
                    subtree: true
                });
            }
        };
        if (document.body) {
            attach();
        } else {
            document.addEventListener('DOMContentLoaded', attach, { once: true });
        }
    }

    /**
     * Добавление кастомной кнопки для select элемента
     * @param {HTMLSelectElement} selectElement - Исходный select элемент
     * @param {number} index - Индекс элемента (для уникальности)
     */
    replaceSelectElement(selectElement, index = 0) {
        if (!selectElement || selectElement.tagName !== 'SELECT') {
            return;
        }

        // Проверяем не был ли уже обработан
        if (selectElement.hasAttribute('data-custom-replaced')) {
            return;
        }

        console.log('Adding custom button for select element:', selectElement);

        // Создаем кастомную кнопку
        const customButton = this.createCustomButton(selectElement, index);
        
        // Добавляем кнопку рядом с select, не меняя его стили
        selectElement.parentNode.insertBefore(customButton, selectElement);
        selectElement.style.display = 'none'; // Только скрываем, не меняем стили
        selectElement.setAttribute('data-custom-replaced', 'true');
        
        // Сохраняем связь
        this.selectElements.set(customButton.id, selectElement);
        
        console.log('Custom button added for select element:', customButton.id);
    }

    /**
     * Создание кастомной кнопки для select элемента
     * @param {HTMLSelectElement} selectElement - Исходный select элемент
     * @param {number} index - Индекс элемента
     * @returns {HTMLButtonElement} Кастомная кнопка
     */
    createCustomButton(selectElement, index) {
        const button = document.createElement('button');
        button.id = 'custom-select-' + index + '-' + Date.now();
        button.className = 'custom-select-button';
        
        // Копируем стили и атрибуты
        this.copyStylesAndAttributes(selectElement, button);
        
        // Устанавливаем текст кнопки
        const selectedOption = selectElement.options[selectElement.selectedIndex];
        button.textContent = selectedOption ? selectedOption.text : 'Выберите опцию';
        
        // Добавляем обработчик клика
        button.addEventListener('click', (e) => {
            e.preventDefault();
            e.stopPropagation();
            this.showCustomDialog(selectElement, button);
        });
        
        return button;
    }

    /**
     * Копирование стилей и атрибутов с select на кнопку
     * @param {HTMLSelectElement} selectElement - Исходный select
     * @param {HTMLButtonElement} button - Целевая кнопка
     */
    copyStylesAndAttributes(selectElement, button) {
        // Копируем только основные стили, не меняя внешний вид select
        const computedStyle = window.getComputedStyle(selectElement);
        const importantStyles = [
            'width', 'height', 'margin', 'padding', 'border', 'border-radius',
            'background-color', 'color', 'font-size', 'font-family', 'font-weight',
            'text-align', 'line-height', 'position'
        ];
        
        importantStyles.forEach(style => {
            const value = computedStyle.getPropertyValue(style);
            if (value && value !== 'initial' && value !== 'inherit') {
                button.style.setProperty(style, value);
            }
        });
        
        // Добавляем только необходимые стили для функциональности кнопки
        button.style.cursor = 'pointer';
        button.style.userSelect = 'none';
        button.style.outline = 'none';
        button.style.display = 'flex';
        button.style.alignItems = 'center';
        button.style.justifyContent = 'space-between';
        
        // Добавляем стрелку вниз
        const arrow = document.createElement('span');
        arrow.innerHTML = '▼';
        arrow.style.marginLeft = '8px';
        arrow.style.fontSize = '12px';
        arrow.style.color = '#666';
        button.appendChild(arrow);
    }

    /**
     * Показать кастомный диалог выбора
     * @param {HTMLSelectElement} selectElement - Исходный select элемент
     * @param {HTMLButtonElement} button - Кастомная кнопка
     */
    showCustomDialog(selectElement, button) {
        console.log('Showing custom dialog for select:', selectElement);
        
        // Собираем опции
        const options = [];
        const values = [];
        let currentValue = '';
        
        for (let i = 0; i < selectElement.options.length; i++) {
            const option = selectElement.options[i];
            options.push(option.text);
            values.push(option.value);
            
            if (option.selected) {
                currentValue = option.value;
            }
        }
        
        // Вызываем Android метод для показа диалога
        if (window.AndroidInterface && window.AndroidInterface.showCustomSelectDialog) {
            const dialogData = {
                title: selectElement.getAttribute('data-title') || 'Выберите опцию',
                options: options,
                values: values,
                currentValue: currentValue,
                selectId: button.id, // Передаем ID кнопки, а не select
                originalSelectId: selectElement.id || selectElement.name || 'unknown'
            };
            
            console.log('Calling Android showCustomSelectDialog with data:', dialogData);
            window.AndroidInterface.showCustomSelectDialog(JSON.stringify(dialogData));
        } else {
            console.warn('AndroidInterface.showCustomSelectDialog not available');
        }
    }

    /**
     * Обновление кнопки после выбора опции
     * @param {string} buttonId - ID кнопки
     * @param {string} selectedValue - Выбранное значение
     * @param {string} selectedText - Выбранный текст
     */
    updateButtonAfterSelection(buttonId, selectedValue, selectedText) {
        console.log('Updating button after selection:', buttonId, selectedValue, selectedText);
        
        // Находим кнопку по ID
        const button = document.getElementById(buttonId);
        if (!button) {
            console.warn('Button not found for buttonId:', buttonId);
            return;
        }
        
        button.textContent = selectedText || '';
        const arrow = document.createElement('span');
        arrow.textContent = '▼';
        arrow.style.marginLeft = '8px';
        arrow.style.fontSize = '12px';
        arrow.style.color = '#666';
        button.appendChild(arrow);
        
        // Находим соответствующий select и обновляем его значение
        const selectElement = this.selectElements.get(buttonId);
        if (selectElement) {
            selectElement.value = selectedValue;
            
            // Триггерим событие change
            const changeEvent = new Event('change', { bubbles: true });
            selectElement.dispatchEvent(changeEvent);
            
            console.log('Select element updated:', selectElement.value);
        } else {
            console.warn('Select element not found for buttonId:', buttonId);
        }
    }

    /**
     * Очистка ресурсов
     */
    destroy() {
        if (this.observer) {
            this.observer.disconnect();
            this.observer = null;
        }
        
        this.selectElements.clear();
        this.isInitialized = false;
        
        console.log('CustomSelectHandler destroyed');
    }
}

if (!window.customSelectHandler) {
    window.customSelectHandler = new CustomSelectHandler();
}

if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', () => {
        window.customSelectHandler.init();
    }, { once: true });
} else {
    window.customSelectHandler.init();
}
'custom_select_ok';
