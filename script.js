// 导航栏吸附效果
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
});

// FAQ折叠面板
document.addEventListener('DOMContentLoaded', function() {
    const faqItems = document.querySelectorAll('.faq-item');
    
    faqItems.forEach(item => {
        const question = item.querySelector('.faq-question');
        
        question.addEventListener('click', function() {
            // 切换当前项的active类
            item.classList.toggle('active');
            
            // 关闭其他所有项
            faqItems.forEach(otherItem => {
                if (otherItem !== item) {
                    otherItem.classList.remove('active');
                }
            });
        });
    });
});

// 平滑滚动
document.addEventListener('DOMContentLoaded', function() {
    // 为所有内部链接添加平滑滚动
    document.querySelectorAll('a[href^="#"]').forEach(anchor => {
        anchor.addEventListener('click', function(e) {
            e.preventDefault();
            
            const targetId = this.getAttribute('href');
            if (targetId === '#') return;
            
            const targetElement = document.querySelector(targetId);
            if (targetElement) {
                window.scrollTo({
                    top: targetElement.offsetTop - 80, // 减去导航栏高度
                    behavior: 'smooth'
                });
            }
        });
    });
});

// 滚动动画
document.addEventListener('DOMContentLoaded', function() {
    // 检查元素是否在视口中
    function isInViewport(element) {
        const rect = element.getBoundingClientRect();
        return (
            rect.top <= (window.innerHeight || document.documentElement.clientHeight) * 0.8 &&
            rect.bottom >= 0
        );
    }
    
    // 添加动画类
    function addAnimation() {
        const elements = document.querySelectorAll('.step, .scenario-card, .download-card, .faq-item');
        
        elements.forEach(element => {
            if (isInViewport(element)) {
                element.style.opacity = '1';
                element.style.transform = 'translateY(0)';
            }
        });
    }
    
    // 初始设置
    const animatedElements = document.querySelectorAll('.step, .scenario-card, .download-card, .faq-item');
    animatedElements.forEach(element => {
        element.style.opacity = '0';
        element.style.transform = 'translateY(30px)';
        element.style.transition = 'opacity 0.6s ease, transform 0.6s ease';
    });
    
    // 初始检查
    addAnimation();
    
    // 滚动时检查
    window.addEventListener('scroll', addAnimation);
});

// 视频播放功能
document.addEventListener('DOMContentLoaded', function() {
    const videoPlaceholder = document.querySelector('.video-placeholder');
    
    videoPlaceholder.addEventListener('click', function() {
        // 在实际应用中，这里可以替换为真实的视频链接
        // 这里仅作为示例，打开哔哩哔哩网站
        window.open('https://www.bilibili.com/', '_blank');
    });
});

// 下载按钮功能
document.addEventListener('DOMContentLoaded', function() {
    const downloadButtons = document.querySelectorAll('.btn-download:not(.disabled)');
    
    downloadButtons.forEach(button => {
        button.addEventListener('click', function(e) {
            e.preventDefault();
            
            // 获取文件名
            const fileName = this.querySelector('.file-name')?.textContent || '手机变音箱安装包';
            
            // 在实际应用中，这里应该跳转到真实的下载链接
            // 这里仅作为示例，显示一个提示
            alert(`您正在下载: ${fileName}\n\n注意：这是演示版本，实际应用中将跳转到真实下载链接。`);
        });
    });
    
    // 禁用的下载按钮
    const disabledButtons = document.querySelectorAll('.btn-download.disabled');
    disabledButtons.forEach(button => {
        button.addEventListener('click', function(e) {
            e.preventDefault();
            alert('该版本正在开发中，敬请期待！');
        });
    });
});

// 首屏元素淡入动效
document.addEventListener('DOMContentLoaded', function() {
    const introContent = document.querySelector('.intro-content');
    const introImage = document.querySelector('.intro-image');
    
    // 设置初始状态
    introContent.style.opacity = '0';
    introContent.style.transform = 'translateX(-30px)';
    introImage.style.opacity = '0';
    introImage.style.transform = 'translateX(30px)';
    
    // 添加过渡效果
    introContent.style.transition = 'opacity 1s ease, transform 1s ease';
    introImage.style.transition = 'opacity 1s ease, transform 1s ease';
    
    // 触发动画
    setTimeout(() => {
        introContent.style.opacity = '1';
        introContent.style.transform = 'translateX(0)';
        
        // 图片延迟一点动画
        setTimeout(() => {
            introImage.style.opacity = '1';
            introImage.style.transform = 'translateX(0)';
        }, 300);
    }, 300);
});

// 背景流动感动效增强
document.addEventListener('DOMContentLoaded', function() {
    const backgroundAnimation = document.querySelector('.background-animation');
    
    // 添加额外的动画元素
    for (let i = 0; i < 3; i++) {
        const additionalWave = document.createElement('div');
        additionalWave.classList.add('additional-wave');
        additionalWave.style.position = 'absolute';
        additionalWave.style.borderRadius = '50%';
        additionalWave.style.background = `radial-gradient(circle, rgba(0, 170, 255, 0.05) 0%, transparent 70%)`;
        additionalWave.style.width = `${300 + i * 200}px`;
        additionalWave.style.height = `${300 + i * 200}px`;
        additionalWave.style.left = `${10 + i * 15}%`;
        additionalWave.style.top = `${20 + i * 10}%`;
        additionalWave.style.animation = `pulse ${8 + i * 2}s infinite ease-in-out`;
        additionalWave.style.animationDelay = `${i * 1.5}s`;
        
        backgroundAnimation.appendChild(additionalWave);
    }
});