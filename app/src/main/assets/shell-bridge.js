// 锁屏播放桥接脚本
// 作用：把 WebView 里的播放状态推给原生（MediaSession），并接收原生指令
// 由壳层在页面加载完成后注入
(function () {
  if (window.__foliaShellBridgeInstalled) return;
  window.__foliaShellBridgeInstalled = true;

  var NATIVE = window.FoliaNative;
  if (!NATIVE) {
    console.warn('[ShellBridge] FoliaNative 不存在，跳过');
    return;
  }

  // ---------- 工具：找页面里的 audio 元素 ----------
  function findAudio() {
    var list = document.querySelectorAll('audio');
    for (var i = 0; i < list.length; i++) {
      var a = list[i];
      // 选有 src/blob srcObject 的那个
      if (a.src || a.currentSrc || a.srcObject) return a;
    }
    return list.length ? list[0] : null;
  }

  // ---------- 工具：从 DOM 读歌名/歌手 ----------
  // Folia 底部播放条通常有 aria-label 或特定结构，这里做多路兜底
  function readMeta() {
    var title = '';
    var artist = '';
    var cover = '';

    // 1) mediaSession 元数据（如果 Folia 设置了，最准）
    try {
      var ms = navigator.mediaSession;
      if (ms && ms.metadata) {
        title = ms.metadata.title || '';
        artist = ms.metadata.artist || '';
        var art = ms.metadata.artwork;
        if (art && art.length) cover = art[art.length - 1].src || '';
      }
    } catch (e) {}

    // 2) 文档标题兜底（Folia 可能把歌名写进 title）
    if (!title) {
      var dt = (document.title || '').trim();
      if (dt && dt.indexOf('Folia') === -1 && dt.indexOf(' - ') > 0) {
        var parts = dt.split(' - ');
        title = parts[0].trim();
        artist = parts[1].trim();
      }
    }

    // 3) 从播放条 DOM 抓（找含"播放"区域的文本）
    if (!title) {
      try {
        // 常见结构：播放条里有个 img(封面) + 两个文本节点
        var bar = document.querySelector('[data-folia-player]')
          || document.querySelector('.player-bar')
          || document.querySelector('[class*="player"]');
        if (bar) {
          var img = bar.querySelector('img');
          if (img && img.src) cover = img.src;
          var texts = [];
          bar.querySelectorAll('div,span,p').forEach(function (n) {
            var t = (n.textContent || '').trim();
            if (t && t.length < 60 && texts.indexOf(t) === -1) texts.push(t);
          });
          // 取前两个短文本当歌名和歌手
          if (texts.length >= 1) title = texts[0];
          if (texts.length >= 2) artist = texts[1];
        }
      } catch (e) {}
    }

    return { title: title, artist: artist, cover: cover };
  }

  // ---------- 定时推送状态 ----------
  var lastSig = '';
  function pushState() {
    var a = findAudio();
    var meta = readMeta();
    var playing = !!(a && !a.paused && !a.ended && a.readyState > 2);
    var duration = a && isFinite(a.duration) ? Math.round(a.duration * 1000) : 0;
    var position = a && isFinite(a.currentTime) ? Math.round(a.currentTime * 1000) : 0;

    var payload = {
      title: meta.title || '',
      artist: meta.artist || '',
      album: '',
      cover: meta.cover || '',
      duration: duration,
      position: position,
      playing: playing
    };

    // 只在有意义变化时上报（进度每 1 秒也会变，所以用秒级签名）
    var sig = payload.title + '|' + payload.artist + '|' + payload.playing
      + '|' + Math.floor(position / 1000) + '|' + duration;
    if (sig === lastSig) return;
    lastSig = sig;

    try {
      NATIVE.onState(JSON.stringify(payload));
    } catch (e) {
      console.warn('[ShellBridge] onState 失败', e);
    }
  }

  setInterval(pushState, 800);

  // ---------- 接收原生指令 ----------
  window.FoliaShell = {
    onCommand: function (cmd) {
      console.log('[ShellBridge] 收到指令', cmd);
      var a = findAudio();

      if (cmd === 'play') {
        if (a) { try { a.play(); } catch (e) {} }
        // 兜底：点页面上的播放按钮
        else { clickByLabel(['播放', 'Play', 'play']); }
        return;
      }
      if (cmd === 'pause') {
        if (a) { try { a.pause(); } catch (e) {} }
        else { clickByLabel(['暂停', 'Pause', 'pause']); }
        return;
      }
      if (cmd === 'next') {
        clickByLabel(['下一首', 'Next', 'next']);
        return;
      }
      if (cmd === 'prev') {
        clickByLabel(['上一首', 'Previous', 'prev']);
        return;
      }
      if (cmd.indexOf('seek:') === 0) {
        var ms = parseInt(cmd.slice(5), 10);
        if (a && isFinite(ms)) { try { a.currentTime = ms / 1000; } catch (e) {} }
        return;
      }
      if (cmd === 'stop') {
        if (a) { try { a.pause(); } catch (e) {} }
        return;
      }
    }
  };

  // 按 aria-label / title / 文本 找按钮并点击
  function clickByLabel(names) {
    try {
      var nodes = document.querySelectorAll('button,[role="button"]');
      for (var i = 0; i < nodes.length; i++) {
        var n = nodes[i];
        var label = (n.getAttribute('aria-label') || n.getAttribute('title') || n.textContent || '').trim();
        for (var j = 0; j < names.length; j++) {
          if (label === names[j] || label.indexOf(names[j]) > -1) {
            n.click();
            return true;
          }
        }
      }
    } catch (e) {}
    return false;
  }

  console.log('[ShellBridge] 锁屏播放桥接已安装');
})();