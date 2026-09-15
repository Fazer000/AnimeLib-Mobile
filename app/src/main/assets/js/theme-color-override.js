(function() {
    try {
        var STYLE_ID = 'animelib-player-theme-colors-override';

        function applyThemeColorOverrides() {
            var docEl = document.documentElement;
            if (!docEl) return;

            var themeAttr = docEl.getAttribute('data-theme');
            var isAuto = (themeAttr === 'auto' || !themeAttr);
            var isDarkAttr = (themeAttr === 'dark');
            var isLightAttr = (themeAttr === 'light');

            var systemIsDark = window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches;

            var computedBg = '';
            try {
                computedBg = window.getComputedStyle(docEl).getPropertyValue('--background').trim().toLowerCase();
            } catch (e) {}

            var isDarkBg = computedBg === '#0a0a0a' || computedBg === '#1c1c1c' || computedBg === '#131316' || computedBg === '#0d0d10' || computedBg === '#0a0a0c' ||
                           computedBg.includes('10, 10, 10') || computedBg.includes('28, 28, 28') || computedBg.includes('19, 19, 22') || computedBg.includes('13, 13, 16') || computedBg.includes('10, 10, 12');

            var shouldApplyDarkPlayerColors = isDarkAttr || (isAuto && systemIsDark) || isDarkBg;

            if (isLightAttr && !isDarkBg) {
                shouldApplyDarkPlayerColors = false;
            }

            var existingStyle = document.getElementById(STYLE_ID);

            if (!shouldApplyDarkPlayerColors) {
                if (existingStyle) {
                    existingStyle.remove();
                    console.log('[AnimeLIB] Removed player dark theme color overrides (Light theme active)');
                }
                return;
            }

            var cssText = `
                :root[data-theme="dark"],
                :root[data-theme="auto"],
                html[data-theme="dark"],
                html[data-theme="auto"],
                body[data-theme="dark"],
                body[data-theme="auto"],
                [data-theme="dark"],
                [data-theme="auto"],
                :root,
                html,
                body {
                    --background: #0a0a0c !important;
                    --background-rgb: 10, 10, 12 !important;
                    --background-dark: #121215 !important;
                    --background-hover: rgba(255, 255, 255, .05) !important;
                    --background-fill-1: rgba(50, 50, 55, .36) !important;
                    --background-fill-2: rgba(50, 50, 55, .32) !important;
                    --background-fill-3: rgba(50, 50, 55, .24) !important;
                    --background-fill-4: rgba(50, 50, 55, .10) !important;
                    
                    --background-elevated-1: rgb(28, 28, 30) !important;
                    --background-elevated-2: rgb(28, 28, 30) !important;
                    --background-elevated-3: rgb(28, 28, 30) !important;
                    --background-elevated-1-rgb: 28, 28, 30 !important;
                    --background-elevated-2-rgb: 28, 28, 30 !important;
                    --background-elevated-3-rgb: 28, 28, 30 !important;

                    --header-color: rgb(28, 28, 30) !important;
                    --header-color-rgb: 28, 28, 30 !important;
                    --header-bg: rgb(28, 28, 30) !important;

                    --foreground: #131316 !important;
                    --foreground-rgb: 19, 19, 22 !important;
                    --foreground-darken: rgba(0, 0, 0, .15) !important;

                    --btn-default-border: rgba(50, 50, 55, .5) !important;
                    --btn-default-bg: rgba(255, 255, 255, .06) !important;
                    --btn-default-bg-hover: rgba(255, 255, 255, .1) !important;
                    --btn-light-border: #3a3a3e !important;
                    --btn-light-bg: rgb(28, 28, 30) !important;
                    --btn-light-bg-hover: rgba(255, 255, 255, .04) !important;

                    --border-base: rgba(50, 50, 55, .65) !important;
                    --border-light: #222226 !important;
                    --border-darker: #3a3a3e !important;
                    --border-opacity: rgba(50, 50, 55, .65) !important;
                    --border-primary: #3a3a3e !important;

                    --input-border: #3a3a3e !important;
                    --input-border-focus: #8852DE !important;
                    --input-bg: #18181c !important;

                    --placeholder-block-bg: rgb(28, 28, 30) !important;
                    --scrollbar-thumb: #3a3a3e !important;
                    --scrollbar-thumb-hover: #505055 !important;
                    --scrollbar: #0a0a0c !important;
                    --comment-highlight: rgb(28, 28, 30) !important;
                    --sticky-comment-collapsed-gradient: rgb(28, 28, 30) !important;
                    --skeleton-bg: rgba(50, 50, 55, .32) !important;
                    --skeleton-bg-secondary: rgba(50, 50, 55, .24) !important;
                    --secondary: rgb(28, 28, 30) !important;
                    --link-border-color: rgba(255, 255, 255, .2) !important;
                }

                @media (prefers-color-scheme: dark) {
                    :root:not([data-theme="light"]),
                    html:not([data-theme="light"]),
                    body:not([data-theme="light"]) {
                        --background: #0a0a0c !important;
                        --background-rgb: 10, 10, 12 !important;
                        --background-dark: #121215 !important;
                        --background-hover: rgba(255, 255, 255, .05) !important;
                        --background-fill-1: rgba(50, 50, 55, .36) !important;
                        --background-fill-2: rgba(50, 50, 55, .32) !important;
                        --background-fill-3: rgba(50, 50, 55, .24) !important;
                        --background-fill-4: rgba(50, 50, 55, .10) !important;
                        
                        --background-elevated-1: rgb(28, 28, 30) !important;
                        --background-elevated-2: rgb(28, 28, 30) !important;
                        --background-elevated-3: rgb(28, 28, 30) !important;
                        --background-elevated-1-rgb: 28, 28, 30 !important;
                        --background-elevated-2-rgb: 28, 28, 30 !important;
                        --background-elevated-3-rgb: 28, 28, 30 !important;

                        --header-color: rgb(28, 28, 30) !important;
                        --header-color-rgb: 28, 28, 30 !important;
                        --header-bg: rgb(28, 28, 30) !important;

                        --foreground: #131316 !important;
                        --foreground-rgb: 19, 19, 22 !important;
                        --foreground-darken: rgba(0, 0, 0, .15) !important;

                        --btn-default-border: rgba(50, 50, 55, .5) !important;
                        --btn-default-bg: rgba(255, 255, 255, .06) !important;
                        --btn-default-bg-hover: rgba(255, 255, 255, .1) !important;
                        --btn-light-border: #3a3a3e !important;
                        --btn-light-bg: rgb(28, 28, 30) !important;
                        --btn-light-bg-hover: rgba(255, 255, 255, .04) !important;

                        --border-base: rgba(50, 50, 55, .65) !important;
                        --border-light: #222226 !important;
                        --border-darker: #3a3a3e !important;
                        --border-opacity: rgba(50, 50, 55, .65) !important;
                        --border-primary: #3a3a3e !important;

                        --input-border: #3a3a3e !important;
                        --input-border-focus: #8852DE !important;
                        --input-bg: #18181c !important;

                        --placeholder-block-bg: rgb(28, 28, 30) !important;
                        --scrollbar-thumb: #3a3a3e !important;
                        --scrollbar-thumb-hover: #505055 !important;
                        --scrollbar: #0a0a0c !important;
                        --comment-highlight: rgb(28, 28, 30) !important;
                        --sticky-comment-collapsed-gradient: rgb(28, 28, 30) !important;
                        --skeleton-bg: rgba(50, 50, 55, .32) !important;
                        --skeleton-bg-secondary: rgba(50, 50, 55, .24) !important;
                        --secondary: rgb(28, 28, 30) !important;
                        --link-border-color: rgba(255, 255, 255, .2) !important;
                    }
                }
            `;

            if (existingStyle) {
                if (existingStyle.textContent !== cssText) {
                    existingStyle.textContent = cssText;
                    console.log('[AnimeLIB] Updated player dark theme color overrides');
                }
                return;
            }

            var styleEl = document.createElement('style');
            styleEl.id = STYLE_ID;
            styleEl.textContent = cssText;

            var target = document.head || document.documentElement || document.body;
            if (target) {
                target.appendChild(styleEl);
                console.log('[AnimeLIB] Successfully applied player dark theme color overrides');
            }
        }

        applyThemeColorOverrides();

        if (document.readyState === 'loading') {
            document.addEventListener('DOMContentLoaded', applyThemeColorOverrides);
        }

        if (window.MutationObserver && document.documentElement) {
            var observer = new MutationObserver(function(mutations) {
                applyThemeColorOverrides();
            });
            observer.observe(document.documentElement, { attributes: true, attributeFilter: ['data-theme', 'class', 'style'] });
        }

        if (window.matchMedia) {
            window.matchMedia('(prefers-color-scheme: dark)').addEventListener('change', function() {
                applyThemeColorOverrides();
            });
        }
    } catch (e) {
        console.error('[AnimeLIB] Error in theme color overrides:', e);
    }
})();
