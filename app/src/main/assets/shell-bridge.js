// 锁屏播放桥接脚本
// 作用：把 WebView 里的播放状态推给原生（MediaSession），并接收原生指令
// 由壳层在页面加载完成后注入
//
// Folia 的真实 DOM 锚点（读源码确认，比 aria-label 稳定）：
//   [data-ponder="player-bar"]          播放条根节点
//   [data-ponder-slots]                 槽位容器
//   [data-ponder-slot="next"|"prev"]    可自定义槽位按钮（可能未启用）
//   components/FloatingPlayerControls.tsx / PlayerControlSlotButton.tsx
// 命令面板 fallback：playback-next / playback-prev，快捷键 n / b
(function () {
  if (window.__foliaShellBridgeInstalled) return;
  window.__foliaShellBridgeInstalled = true;

  var NATIVE = window.FoliaNative;
  if (!NATIVE) {
    console.warn('[ShellBridge] FoliaNative 不存在，跳过');
    return;
  }

  function log(msg) {
    try { NATIVE.log(String(msg)); } catch (e) {}
    try { console.log('[ShellBridge] ' + msg); } catch (e) {}
  }

  // ---------- 启动时：强制锁定歌词来源为本音源 ----------
  // Folia 的 autoMatchBestLyric 硬编码只问 netease / kugou / qq，
  // 开关开着就会把汽水的歌词覆盖成别家的。这里直接关掉。
  try {
    if (window.localStorage.getItem('auto_use_best_lyric') !== 'false') {
      window.localStorage.setItem('auto_use_best_lyric', 'false');
      log('已关闭 auto_use_best_lyric（歌词锁定当前音源）');
    }
  } catch (e) {}

  // ---------- 工具：找页面里的 audio 元素 ----------
  function findAudio() {
    var list = document.querySelectorAll('audio');
    for (var i = 0; i < list.length; i++) {
      var a = list[i];
      if (a.src || a.currentSrc || a.srcObject) return a;
    }
    return list.length ? list[0] : null;
  }

  // ---------- 工具：从 Folia 播放条读歌名/歌手/封面 ----------
  // 真实结构（读源码 + DIAG 实测确认）：
  //   收起态：[data-ponder="player-bar"] 内
  //     TrackTitleNavigator -> div.group/title > div > div > span  ← 歌名纯文本
  //   展开态：div.truncate.text-center.text-sm.font-bold          ← 歌名纯文本
  //   播放条内 img                                                ← 封面
  // 注意：歌名没有 title 属性，只能读 textContent。
  function readMeta() {
    var title = '';
    var artist = '';
    var cover = '';

    // 1) navigator.mediaSession（Folia 目前不设，留作兜底）
    try {
      var ms = navigator.mediaSession;
      if (ms && ms.metadata) {
        title = ms.metadata.title || '';
        artist = ms.metadata.artist || '';
        var art = ms.metadata.artwork;
        if (art && art.length) cover = art[art.length - 1].src || '';
      }
    } catch (e) {}

    var bar = null;
    try { bar = document.querySelector('[data-ponder="player-bar"]'); } catch (e) {}

    if (bar) {
      // 歌名：优先展开态的 .font-bold，其次标题导航器里的 span
      if (!title) {
        var bold = bar.querySelector('.font-bold');
        if (bold) {
          var bt = (bold.textContent || '').trim();
          if (bt && bt.length <= 100) title = bt;
        }
      }
      if (!title) {
        // TrackTitleNavigator: .group\/title 下的 span
        var g = bar.querySelector('[class*="group/title"]') || bar;
        var spans = g.querySelectorAll('span');
        for (var i = 0; i < spans.length; i++) {
          var sp = spans[i];
          if (sp.children.length > 0) continue;
          var st = (sp.textContent || '').trim();
          if (!st || st.length > 100) continue;
          if (/^\d{1,2}:\d{2}/.test(st)) continue;
          title = st;
          break;
        }
      }
      // 封面
      if (!cover) {
        var img = bar.querySelector('img');
        if (img && img.src) cover = img.src;
      }
    }

    // 歌手：播放条通常不显示歌手，退而从页面其它地方取
    // Folia 展开播放条里有 "歌名 - 歌手 - 专辑" 形式，或用 audio 的 data 属性
    if (!artist) {
      try {
        var a = findAudio();
        if (a) {
          artist = a.getAttribute('data-artist') || '';
        }
      } catch (e) {}
    }

    // 最后兜底：document.title "歌名 - 歌手"
    if (!title) {
      var dt = (document.title || '').trim();
      if (dt && dt.indexOf(' - ') > 0) {
        var parts = dt.split(' - ');
        title = parts[0].trim();
        if (!artist) artist = parts[1].trim();
      }
    }

    return { title: title, artist: artist, cover: cover };
  }

  // ---------- 工具：抓当前歌词（用于锁屏歌词动画） ----------
  // Folia 播放器页会把歌词渲染成一行行 DOM，当前行高亮。
  // 这里抓「当前行文本 + 上下文几行」，供原生层做滚动动画。
  function readLyrics() {
    var cur = '';
    var next = '';
    try {
      // 当前高亮行：Folia 用 data-active / aria-current 或色值区分
      var cands = document.querySelectorAll(
        '[data-active="true"],[aria-current="true"],[class*="lyric"] [class*="active"]'
      );
      for (var i = 0; i < cands.length; i++) {
        var t = (cands[i].textContent || '').trim();
        if (t && t.length < 200) { cur = t; break; }
      }
    } catch (e) {}
    return { current: cur, next: next };
  }

  // ---------- 定时推送状态 ----------
  var lastSig = '';
  var diagCount = 0;
  function pushState() {
    var a = findAudio();
    var meta = readMeta();
    var lyricText = readLyrics().current;
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
      playing: playing,
      lyric: lyricText
    };

    // 秒级签名去重，避免每 800ms 都跨进程调用
    var sig = payload.title + '|' + payload.artist + '|' + payload.playing
      + '|' + Math.floor(position / 1000) + '|' + duration + '|' + payload.lyric;
    if (sig === lastSig) return;
    lastSig = sig;

    // 诊断：前 3 次上报若 title 为空，打印页面结构帮助定位
    if (!payload.title && diagCount < 3) {
      diagCount++;
      try {
        var bars = document.querySelectorAll('[data-ponder="player-bar"]');
        var allTitle = document.querySelectorAll('[title]:not([title=""])');
        var sample = [];
        for (var q = 0; q < Math.min(allTitle.length, 12); q++) {
          var nd = allTitle[q];
          sample.push(
            (nd.tagName || '?').toLowerCase()
            + '[title=' + (nd.getAttribute('title') || '').slice(0, 20) + ']'
            + (nd.closest && nd.closest('button') ? '{inBtn}' : '')
          );
        }
        log('DIAG bar=' + bars.length
          + ' audio=' + !!a
          + ' titled=' + allTitle.length
          + ' sample=' + sample.join(' | '));
      } catch (e) {
        log('DIAG 失败 ' + e);
      }
    }

    try {
      NATIVE.onState(JSON.stringify(payload));
    } catch (e) {
      console.warn('[ShellBridge] onState 失败', e);
    }
  }

  setInterval(pushState, 800);
  setTimeout(pushState, 1200);

  // ---------- 指令实现 ----------

  // A) 直接点 Folia 的可自定义槽位（若用户把 next/prev 放进了槽位）
  function clickSlot(actionId) {
    try {
      var btn = document.querySelector('[data-ponder-slot="' + actionId + '"]');
      if (btn && !btn.disabled) {
        btn.click();
        return true;
      }
    } catch (e) {}
    return false;
  }

  // B) 靠 aria-label / title 匹配（中英文都覆盖）
  function clickByLabel(names) {
    var nodes = document.querySelectorAll('button,[role="button"]');
    for (var i = 0; i < nodes.length; i++) {
      var n = nodes[i];
      if (n.disabled) continue;
      var label = (n.getAttribute('aria-label') || n.getAttribute('title') || n.textContent || '').trim();
      if (!label) continue;
      for (var j = 0; j < names.length; j++) {
        if (label === names[j] || label.indexOf(names[j]) > -1) {
          n.click();
          return true;
        }
      }
    }
    return false;
  }

  // C) 发送键盘事件（Folia 命令面板快捷键：n=下一首 b=上一首）
  function sendKey(key) {
    try {
      var up = key.toUpperCase();
      var code = up.charCodeAt(0);
      var targets = [document.activeElement, document.body, document.documentElement];
      for (var i = 0; i < targets.length; i++) {
        var t = targets[i];
        if (!t) continue;
        ['keydown', 'keypress', 'keyup'].forEach(function (type) {
          var ev = new KeyboardEvent(type, {
            key: key, code: 'Key' + up,
            keyCode: code, which: code,
            bubbles: true, cancelable: true
          });
          t.dispatchEvent(ev);
        });
      }
      return true;
    } catch (e) {
      return false;
    }
  }

  // E) 走命令面板执行模式：按 ':' 打开，输入 vim 快捷键，回车
  //    executeShortcut 只有命令面板打开时才生效（见 executeShortcuts.ts）
  function runViaPalette(key) {
    // 1) ':' 打开执行模式
    sendKey(':');
    log('已发送 : 打开命令面板');

    setTimeout(function () {
      // 2) 找到命令面板的输入框并输入快捷键
      var input = document.querySelector(
        'input[placeholder*="命令"], input[placeholder*="ommand"], ' +
        '[role="dialog"] input[type="text"], input[type="text"]'
      );
      if (!input) {
        log('未找到命令面板输入框，仅发送了 :');
        return;
      }
      try {
        input.focus();
        // React 受控输入：用原生 setter 赋值再派发 input 事件
        var setter = Object.getOwnPropertyDescriptor(
          window.HTMLInputElement.prototype, 'value'
        ).set;
        setter.call(input, key);
        input.dispatchEvent(new Event('input', { bubbles: true }));
        log('命令面板已输入 ' + key);
      } catch (e) {
        log('输入快捷键失败 ' + e);
        return;
      }

      // 3) 回车执行
      setTimeout(function () {
        try {
          ['keydown', 'keypress', 'keyup'].forEach(function (type) {
            input.dispatchEvent(new KeyboardEvent(type, {
              key: 'Enter', code: 'Enter', keyCode: 13, which: 13,
              bubbles: true, cancelable: true
            }));
          });
          log('已发送回车执行 ' + key);
        } catch (e) {}
      }, 180);
    }, 220);
  }

  function doNext() {
    // 1) 优先：用户如果把 next 放进了可配置槽位
    if (clickSlot('next')) { log('next: 命中 data-ponder-slot'); return; }
    // 2) 次选：页面上真有 aria-label 是"下一首"的按钮
    if (clickByLabel(['下一首', '下一曲', 'Next track', 'Skip next'])) {
      log('next: 命中 aria-label');
      return;
    }
    // 3) 兜底：命令面板执行模式（最可靠的程序化入口）
    runViaPalette('n');
  }

  function doPrev() {
    if (clickSlot('prev')) { log('prev: 命中 data-ponder-slot'); return; }
    if (clickByLabel(['上一首', '上一曲', 'Previous track', 'Skip back'])) {
      log('prev: 命中 aria-label');
      return;
    }
    runViaPalette('b');
  }

  // ---------- 接收原生指令 ----------
  window.FoliaShell = {
    onCommand: function (cmd) {
      log('收到指令 ' + cmd);
      var a = findAudio();

      if (cmd === 'play') {
        if (a && a.src) {
          try { a.play(); } catch (e) {}
        } else {
          clickByLabel(['播放', 'Play']);
        }
        return;
      }
      if (cmd === 'pause') {
        if (a) { try { a.pause(); } catch (e) {} }
        else { clickByLabel(['暂停', 'Pause']); }
        return;
      }
      if (cmd === 'next') { doNext(); return; }
      if (cmd === 'prev') { doPrev(); return; }
      if (cmd === 'stop') {
        if (a) { try { a.pause(); } catch (e) {} }
        return;
      }
      if (cmd.indexOf('seek:') === 0) {
        var ms = parseInt(cmd.slice(5), 10);
        if (a && isFinite(ms)) { try { a.currentTime = ms / 1000; } catch (e) {} }
        return;
      }
    },

    // 供调试/未来扩展
    _debug: function () {
      return JSON.stringify({
        audio: !!findAudio(),
        meta: readMeta(),
        bar: !!document.querySelector('[data-ponder="player-bar"]'),
        slots: Array.prototype.map.call(
          document.querySelectorAll('[data-ponder-slot]'),
          function (n) { return n.getAttribute('data-ponder-slot'); }
        )
      });
    }
  };

  log('锁屏播放桥接已安装');
})();