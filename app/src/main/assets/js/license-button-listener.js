/**
 * Класс для обработки кнопки "Лицензирован"
 */
class LicenseButtonHandler {
    constructor() {
        this.isSetup = false;
        this.init();
    }

    /**
     * Инициализация обработчика
     */
    init() {
        if (this.isSetup) {
            console.log('[LicenseButtonHandler] Already setup');
            return;
        }

        const host = (window.location && window.location.hostname) ? window.location.hostname.toLowerCase() : '';
        if (host.indexOf('animelib') === -1) {
            console.log('[LicenseButtonHandler] Skipping setup for non-animelib domain: ' + host);
            return;
        }

        this.isSetup = true;
        this.setupClickListener();
        this.setupTextReplacement();
        this.replaceExistingButtons();

        console.log('[LicenseButtonHandler] Setup completed');
    }

    /**
     * Настраивает обработчик кликов
     */
    setupClickListener() {
        document.addEventListener('click', (e) => {
            const startEl = (e.target && e.target.nodeType === 1) ? e.target : (e.target ? e.target.parentElement : null);
            if (!startEl) return;
            const button = typeof startEl.closest === 'function' ? startEl.closest('button') : null;
            if (!button) return;

            const buttonText = this.getButtonText(button).trim();
            if (buttonText === 'Лицензирован') {
                e.preventDefault();
                e.stopPropagation();
                if (e.stopImmediatePropagation) e.stopImmediatePropagation();
                AndroidInterface.onPlayerButtonClicked(window.location.href);
            }
        }, true);
    }

    /**
     * Настраивает замену текста для динамически добавляемых кнопок
     */
    setupTextReplacement() {
        // MutationObserver для отслеживания новых кнопок
        const observer = new MutationObserver((mutations) => {
            mutations.forEach((mutation) => {
                if (mutation.type === 'childList') {
                    mutation.addedNodes.forEach((node) => {
                        if (node.nodeType === Node.ELEMENT_NODE) {
                            // Проверяем добавленные кнопки
                            const buttons = node.querySelectorAll ? node.querySelectorAll('button') : [];
                            buttons.forEach((button) => {
                                this.replaceButtonText(button);
                            });
                            
                            // Проверяем саму добавленную ноду если это кнопка
                            if (node.tagName === 'BUTTON') {
                                this.replaceButtonText(node);
                            }
                        }
                    });
                }
            });
        });

        const attach = () => {
            if (document.body) {
                observer.observe(document.body, {
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
     * Заменяет текст в существующих кнопках
     */
    replaceExistingButtons() {
        const buttons = document.querySelectorAll('button');
        buttons.forEach((button) => {
            this.replaceButtonText(button);
        });

        console.log('[LicenseButtonHandler] Existing buttons processed:', buttons.length);
    }

    /**
     * Заменяет текст кнопки "Лицензирован" на "Смотреть"
     */
    replaceButtonText(button) {
        const buttonText = this.getButtonText(button);
        
        if (buttonText.trim() === 'Лицензирован') {
            const span = button.querySelector('span');
            if (span) {
                span.textContent = 'Смотреть';
                console.log('[LicenseButtonHandler] Changed span text to "Смотреть"');
            } else {
                button.textContent = 'Смотреть';
                console.log('[LicenseButtonHandler] Changed button text to "Смотреть"');
            }
        }
    }

    /**
     * Получает текст кнопки (из span или самой кнопки)
     */
    getButtonText(button) {
        const span = button.querySelector('span');
        if (span) {
            return span.textContent || span.innerText || '';
        } else {
            return button.textContent || button.innerText || '';
        }
    }
}

// Инициализация
try {
    console.log('[LicenseButtonHandler] Starting initialization');
    
    if (window.licenseButtonHandler) {
        console.log('[LicenseButtonHandler] Already initialized');
    } else {
        window.licenseButtonHandler = new LicenseButtonHandler();
    }
    
    'setup_ok';
} catch (e) {
    console.error('[LicenseButtonHandler] Error:', e.message);
    'error: ' + e.message;
}