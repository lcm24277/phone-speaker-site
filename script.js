// 导航栏滚动效果
window.addEventListener('scroll', function() {
    const navbar = document.getElementById('navbar');
    if (window.scrollY > 50) {
        navbar.classList.add('scrolled');
    } else {
        navbar.classList.remove('scrolled');
    }
});

// 移动端菜单切换
document.addEventListener('DOMContentLoaded', function() {
    const navbarToggle = document.querySelector('.navbar-toggle');
    const navbarMenu = document.querySelector('.navbar-menu');

    navbarToggle.addEventListener('click', function() {
        navbarMenu.classList.toggle('active');
    });

    // 点击菜单项后关闭菜单
    const menuItems = document.querySelectorAll('.navbar-menu a');
    menuItems.forEach(item => {
        item.addEventListener('click', function() {
            navbarMenu.classList.remove('active');
        });
    });

    // 初始化语言切换
    initLanguageSwitcher();
    
    // 初始化反馈链接
    initFeedbackLinks();
    
    // 初始化FAQ折叠面板
    initFAQ();
    
    // 初始化视频播放
    initVideoPlayer();
    
    // 初始化滚动动画
    initScrollAnimations();
});

// 语言切换功能
function initLanguageSwitcher() {
    const langZh = document.getElementById('lang-zh');
    const langEn = document.getElementById('lang-en');
    const langElements = document.querySelectorAll('.lang');
    
    // 检测浏览器语言
    const browserLang = navigator.language || navigator.userLanguage;
    const isChinese = browserLang.includes('zh');
    
    // 设置初始语言
    setLanguage(isChinese ? 'zh' : 'en');
    
    // 中文按钮点击事件
    langZh.addEventListener('click', function() {
        setLanguage('zh');
        langZh.classList.add('active');
        langEn.classList.remove('active');
    });
    
    // 英文按钮点击事件
    langEn.addEventListener('click', function() {
        setLanguage('en');
        langEn.classList.add('active');
        langZh.classList.remove('active');
    });
    
    // 设置语言函数
    function setLanguage(lang) {
        langElements.forEach(el => {
            if (el.getAttribute('data-lang') === lang) {
                el.style.display = 'inline';
            } else {
                el.style.display = 'none';
            }
        });
        
        // 更新反馈链接
        updateFeedbackLinks(lang);
        
        // 保存语言偏好到本地存储
        localStorage.setItem('preferredLanguage', lang);
    }
}

// 初始化反馈链接
function initFeedbackLinks() {
    const feedbackLinks = document.querySelectorAll('#feedback-link, #footer-feedback-link');
    
    feedbackLinks.forEach(link => {
        link.addEventListener('click', function(e) {
            e.preventDefault();
            const lang = document.querySelector('.lang-btn.active').id === 'lang-zh' ? 'zh' : 'en';
            const url = lang === 'zh' ? 'https://tally.so/r/jaWqeY' : 'https://tally.so/r/WOYkzk';
            window.open(url, '_blank');
        });
    });
}

// 更新反馈链接
function updateFeedbackLinks(lang) {
    const feedbackLinks = document.querySelectorAll('#feedback-link, #footer-feedback-link');
    feedbackLinks.forEach(link => {
        link.href = lang === 'zh' ? 'https://tally.so/r/jaWqeY' : 'https://tally.so/r/WOYkzk';
    });
}

// FAQ折叠面板功能
function initFAQ() {
    const faqItems = document.querySelectorAll('.faq-item');
    
    faqItems.forEach(item => {
        const question = item.querySelector('.faq-question');
        
        question.addEventListener('click', function() {
            // 切换当前项的状态
            item.classList.toggle('active');
            
            // 关闭其他所有项
            faqItems.forEach(otherItem => {
                if (otherItem !== item) {
                    otherItem.classList.remove('active');
                }
            });
        });
    });
}

// 视频播放功能
function initVideoPlayer() {
    const videoPlaceholder = document.querySelector('.video-placeholder');
    
    videoPlaceholder.addEventListener('click', function() {
        // 在实际项目中，这里应该打开哔哩哔哩视频
        // 由于没有实际的视频链接，这里只是模拟打开行为
        alert('视频将在哔哩哔哩打开');
        // window.open('https://www.bilibili.com', '_blank');
    });
}

// 滚动动画功能
function initScrollAnimations() {
    const animateElements = document.querySelectorAll('.step, .scenario-card, .download-card, .faq-item');
    
    const observer = new IntersectionObserver((entries) => {
        entries.forEach(entry => {
            if (entry.isIntersecting) {
                entry.target.style.opacity = '1';
                entry.target.style.transform = 'translateY(0)';
            }
        });
    }, {
        threshold: 0.1
    });
    
    animateElements.forEach(element => {
        // 设置初始状态
        element.style.opacity = '0';
        element.style.transform = 'translateY(30px)';
        element.style.transition = 'opacity 0.6s ease, transform 0.6s ease';
        
        // 开始观察
        observer.observe(element);
    });
}

// 平滑滚动功能
document.querySelectorAll('a[href^="#"]').forEach(anchor => {
    anchor.addEventListener('click', function(e) {
        e.preventDefault();
        
        const targetId = this.getAttribute('href');
        if (targetId === '#') return;
        
        const targetElement = document.querySelector(targetId);
        if (targetElement) {
            // 计算导航栏高度，确保内容不被遮挡
            const navbarHeight = document.getElementById('navbar').offsetHeight;
            const targetPosition = targetElement.getBoundingClientRect().top + window.pageYOffset - navbarHeight;
            
            window.scrollTo({
                top: targetPosition,
                behavior: 'smooth'
            });
        }
    });
});

// 下载按钮点击事件
document.querySelectorAll('.btn-download').forEach(button => {
    button.addEventListener('click', function(e) {
        // 如果是禁用的按钮，阻止默认行为
        if (this.classList.contains('disabled')) {
            e.preventDefault();
            return;
        }
        
        // 对于iOS版本的提示
        if (this.classList.contains('ios')) {
            e.preventDefault();
            alert('iOS版本即将推出，请耐心等待');
        }
    });
});

// 页面加载完成后的初始化
window.addEventListener('load', function() {
    // 显示页面
    document.body.style.opacity = '1';
    
    // 检查本地存储中的语言偏好
    const savedLang = localStorage.getItem('preferredLanguage');
    if (savedLang) {
        const langZh = document.getElementById('lang-zh');
        const langEn = document.getElementById('lang-en');
        
        if (savedLang === 'zh') {
            langZh.click();
        } else {
            langEn.click();
        }
    }
});

// 防止页面闪烁的初始样式
document.addEventListener('DOMContentLoaded', function() {
    document.body.style.opacity = '1';
});