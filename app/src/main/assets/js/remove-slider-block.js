(function() {
    function removeSliderBlock() {
        var blocks = document.querySelectorAll('div.section[data-home-block="slider"]');
        for (var i = 0; i < blocks.length; i++) {
            blocks[i].remove();
        }
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', removeSliderBlock);
    } else {
        removeSliderBlock();
    }

    // Периодическая проверка на случай динамической подгрузки SPA
    var observer = new MutationObserver(function(mutations) {
        removeSliderBlock();
    });

    if (document.body) {
        observer.observe(document.body, { childList: true, subtree: true });
    } else {
        document.addEventListener('DOMContentLoaded', function() {
            observer.observe(document.body, { childList: true, subtree: true });
        });
    }
})();
