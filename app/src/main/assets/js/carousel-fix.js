(function() {
    if (window.__carouselFixInjected) return;
    window.__carouselFixInjected = true;

    var style = document.createElement('style');
    style.id = 'animelib-carousel-fix-style';
    style.innerHTML = `
        * {
            -webkit-tap-highlight-color: transparent;
        }
        img, a {
            -webkit-user-drag: none !important;
            user-drag: none !important;
        }
        .swiper, .swiper-container, .swiper-wrapper, .swiper-slide,
        .carousel, .owl-carousel, .slick-slider, .slick-list, .slick-track,
        [class*="carousel"], [class*="slider"], [class*="swiper"], [class*="scroll-x"],
        [style*="overflow-x"], [style*="overflow: auto"] {
            -webkit-overflow-scrolling: touch !important;
            touch-action: pan-x pan-y !important;
            overscroll-behavior-x: contain !important;
        }
    `;
    
    function injectStyle() {
        if (document.head && !document.getElementById('animelib-carousel-fix-style')) {
            document.head.appendChild(style);
        }
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', injectStyle);
    } else {
        injectStyle();
    }

    function initTouchFix() {
        var startX = 0, startY = 0, disallowed = false;
        document.addEventListener('touchstart', function(e) {
            disallowed = false;
            if (e.touches && e.touches.length === 1) {
                startX = e.touches[0].clientX;
                startY = e.touches[0].clientY;
            }
        }, { passive: true });

        document.addEventListener('touchmove', function(e) {
            if (disallowed || !e.touches || e.touches.length !== 1) return;
            var dx = Math.abs(e.touches[0].clientX - startX);
            var dy = Math.abs(e.touches[0].clientY - startY);
            if (dx > dy && dx > 8) {
                disallowed = true;
                if (window.AndroidInterface && window.AndroidInterface.disallowInterceptTouch) {
                    window.AndroidInterface.disallowInterceptTouch();
                }
            }
        }, { passive: true });
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', initTouchFix);
    } else {
        initTouchFix();
    }
})();
